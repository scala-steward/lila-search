package lila.search
package ingestor

import cats.effect.IO
import cats.syntax.all.*
import org.typelevel.log4cats.syntax.*
import org.typelevel.log4cats.{ Logger, LoggerFactory }

import java.time.Instant

trait ForumIngestor:
  // watch change events from MongoDB and ingest forum posts into elastic search
  def watch: IO[Unit]
  // Fetch posts in [since, until] and ingest into elastic search
  def run(since: Instant, until: Instant, dryRun: Boolean): IO[Unit]

object ForumIngestor:

  private val index = Index.Forum

  def apply(forums: Forums, store: KVStore, config: IngestorConfig.Forum)(using
      LoggerFactory[IO],
      ESClient[IO]
  ): ForumIngestor = new:

    given Logger[IO] = LoggerFactory[IO].getLogger

    def watch: IO[Unit] =
      fs2.Stream
        .eval(startAt.flatTap(since => info"Starting forum ingestor from $since"))
        .flatMap: last =>
          forums
            .watch(last)
            .evalMap: result =>
              storeBulk(index, result.toIndex)
                *> deleteMany(index, result.toDelete)
                *> saveLastIndexedTimestamp(result.timestamp.getOrElse(Instant.now()))
        .compile
        .drain

    def run(since: Instant, until: Instant, dryRun: Boolean): IO[Unit] =
      forums
        .fetch(since, until)
        .evalMap: result =>
          dryRun.fold(
            result.toIndex.traverse_(doc => debug"Would index $doc")
              *> result.toDelete.traverse_(doc => debug"Would delete $doc"),
            storeBulk(index, result.toIndex) *> deleteMany(index, result.toDelete)
          )
        .compile
        .drain

    private def saveLastIndexedTimestamp(time: Instant): IO[Unit] =
      store.put(index.value, time)
        *> info"Stored last indexed time ${time.getEpochSecond} for $index"

    private def startAt: IO[Option[Instant]] =
      config.startAt.fold(store.get(index.value))(_.some.pure[IO])
