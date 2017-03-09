package com.ctheu.couchbase

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.model.{ContentTypes, HttpEntity}
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream._
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Source, SourceQueueWithComplete, ZipWith}
import akka.stream.stage.{GraphStage, GraphStageLogic, InHandler, OutHandler}
import com.ctheu.couchbase.graphstages.{Counter, DeltaCounter}
import de.heikoseeberger.akkasse.ServerSentEvent
import play.api.libs.json.Json

import scala.concurrent.Promise
import scala.language.postfixOps






object UI {
  case class KeyWithCounters(total: Long, lastDelta: Long)
  type SimpleKey = KeyWithCounters
  case class Combinaison(mutations: SimpleKey, deletions: SimpleKey, expirations: SimpleKey)

  def withCounters[Out, Mat](source: Source[Out, Mat]): Source[KeyWithCounters, Mat] = {
    Source.fromGraph(GraphDSL.create(source) { implicit b => s =>
      import GraphDSL.Implicits._
      val broadcast = b.add(Broadcast[Out](2))
      def sumPerTick = b.add(Flow.fromGraph(new DeltaCounter[Out]()))
      def sumTotal = b.add(Flow.fromGraph(new Counter[Out]()))
      val zip = b.add(ZipWith((total: Long, last: Long) => KeyWithCounters(total, last)))

      s ~> broadcast
           broadcast ~> sumTotal   ~> zip.in0
           broadcast ~> sumPerTick ~> zip.in1

      SourceShape(zip.out)
    })
  }

  def route()(implicit sys: ActorSystem, mat: Materializer): Route = {
    import akka.http.scaladsl.server.Directives._
    import de.heikoseeberger.akkasse.EventStreamMarshalling._

    import concurrent.duration._

    implicit val ec = sys.dispatcher
    implicit val keysJson = Json.writes[KeyWithCounters]
    implicit val combinaisonJson2 = Json.writes[Combinaison]
    val DEFAULT_DURATION: FiniteDuration = 200 millis

    implicit val durationMarshaller = Unmarshaller.strict[String, FiniteDuration](s => FiniteDuration(s.toInt, "ms"))

    path("events" / """[-a-z0-9\._]+""".r / """[-a-z0-9_]+""".r) { (host, bucket) =>
      get {
        parameter('interval.as[FiniteDuration] ?) { interval =>
          complete {
            val (mutations, deletions, expirations) = CouchbaseSource.createSources()
            val mutationsWithCounts = withCounters(mutations)
            val deletionsWithCounts = withCounters(deletions)
            val expirationsWithCounts = withCounters(expirations)

            val allCounters = GraphDSL.create(mutationsWithCounts, deletionsWithCounts, expirationsWithCounts)((_, _, _)) { implicit b =>
              (m, d, e) =>
                import GraphDSL.Implicits._
                val zip = b.add(ZipWith[SimpleKey, SimpleKey, SimpleKey, (SimpleKey, SimpleKey, SimpleKey)]((_, _, _)))
                m ~> zip.in0
                d ~> zip.in1
                e ~> zip.in2
                SourceShape(zip.out)
            }

            val promise = Promise[(SourceQueueWithComplete[String], SourceQueueWithComplete[String], SourceQueueWithComplete[String])]()
            promise.future.foreach { case (m, d, e) => CouchbaseSource.fill(host, bucket, m, d, e) }

            Source.tick(1 second, interval.getOrElse(DEFAULT_DURATION), NotUsed)
              .zipWithMat(allCounters)(Keep.right)(Keep.right)
              .map { case (m: SimpleKey, d: SimpleKey, e: SimpleKey) => ServerSentEvent(Json.toJson(Combinaison(m, d, e)).toString()) }
              .keepAlive(1 second, () => ServerSentEvent.heartbeat)
              .mapMaterializedValue { x => promise.trySuccess(x); x }
          }
        }
      }
    } ~ path("ui" / """[-a-z0-9\._]+""".r / """[-a-z0-9_]+""".r) { (host, bucket) =>
      get {
        parameter('interval.as[Int] ?) { interval =>
          complete(HttpEntity(ContentTypes.`text/html(UTF-8)`,
            s"""
               |<html>
               |<head>
               |<style>
               |.type { display: flex; }
               |.type span { font-weight: bold; font-size: 20px; }}
               |</style>
               |</head>
               |<body>
               |<h1>Bucket: $bucket</h1>
               |<h3>Mutations</h3>
               |<div class="type"><canvas id="mutation" width="400" height="100"></canvas><div>Total: <span id="muttotal"></span></div></div>
               |<h3>Deletions</h3>
               |<div class="type"><canvas id="deletion" width="400" height="100"></canvas><div>Total: <span id="deltotal"></span></div></div>
               |<h3>Expirations</h3>
               |<div class="type"><canvas id="expiration" width="400" height="100"></canvas><div>Total: <span id="exptotal"></span></div></div>
               |<h3>Last 10 documents mutated (key, expiry)</h3>
               |<pre id="lastMutation"></pre>
               |</div>
               |<script src="//cdnjs.cloudflare.com/ajax/libs/smoothie/1.27.0/smoothie.min.js"></script>
               |<script>
               |function ch(id) {
               |const chart = new SmoothieChart({millisPerPixel:100,maxValueScale:1.5,grid:{strokeStyle:'rgba(119,119,119,0.43)'}});
               |const series = new TimeSeries();
               |chart.addTimeSeries(series, { strokeStyle: 'rgba(0, 255, 0, 1)', fillStyle: 'rgba(0, 255, 0, 0.2)', lineWidth: 1 });
               |chart.streamTo(document.getElementById(id), 1000);
               |return series;
               |}
               |const mut = ch("mutation")
               |const del = ch("deletion")
               |const exp = ch("expiration")
               |
               |function update(sel, value) { document.getElementById(sel + "total").innerHTML = value; }
               |var source = new EventSource('/events/$host/$bucket?${interval.map("interval=" + _).getOrElse("")}');
               |source.addEventListener('message', function(e) {
               |  var data = JSON.parse(e.data);
               |  mut.append(new Date().getTime(), data.mutations.lastDelta); update("mut", data.mutations.total);
               |  del.append(new Date().getTime(), data.deletions.lastDelta); update("del", data.deletions.total);
               |  exp.append(new Date().getTime(), data.expirations.lastDelta); update("exp", data.expirations.total);
               |  //document.getElementById("lastMutation").innerHTML = data.lastDocsMutated.reduce((acc, x) => acc + x + "<br>", "");
               |}, false);
               |</script>
               |</body>
               |</html>
               |
            """.stripMargin
          ))
        }
      }
    }
  }
}
