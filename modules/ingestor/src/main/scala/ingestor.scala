package lila.search
package ingestor

import cats.effect.*
import cats.syntax.all.*
import mongo4cats.database.MongoDatabase
import org.typelevel.log4cats.Logger

trait Ingestor:
  def run(): IO[Unit]

object Ingestor:

  def apply1(
      lichess: MongoDatabase[IO],
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      elastic: ESClient[IO],
      store: KVStore,
      config: IngestorConfig
  )(using Logger[IO]): IO[Ingestor] =
    (
      ForumIngestor(lichess, elastic, store, config.forum),
      TeamIngestor(lichess, elastic, store, config.team),
      StudyIngestor(study, local, elastic, store, config.study),
      GameIngestor(lichess, elastic, store, config.game)
    ).mapN: (forum, team, study, game) =>
      new Ingestor:
        def run() =
          fs2
            .Stream(forum.watch, team.watch, study.watch, game.watch)
            .covary[IO]
            .parJoinUnbounded
            .compile
            .drain

  def apply(
      lichess: MongoDatabase[IO],
      study: MongoDatabase[IO],
      local: MongoDatabase[IO],
      elastic: ESClient[IO],
      store: KVStore,
      config: IngestorConfig
  )(using Logger[IO]): IO[Ingestor] =
    GameIngestor(lichess, elastic, store, config.game)
      .map: game =>
        new Ingestor:
          def run() =
            game.watch.compile.drain
