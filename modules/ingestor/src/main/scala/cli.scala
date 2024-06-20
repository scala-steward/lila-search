package lila.search
package ingestor

import cats.data.Validated
import cats.effect.*
import cats.syntax.all.*
import com.monovore.decline.*
import com.monovore.decline.effect.*
import lila.search.ingestor.opts.IndexConfig
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import java.time.Instant

// TODO: support flags: dry, verbose
object cli
    extends CommandIOApp(
      name = "lila-search-cli",
      header = "CLI for lila-search",
      version = "3.0.0"
    ):

  given Logger[IO] = Slf4jLogger.getLogger[IO]

  override def main: Opts[IO[ExitCode]] =
    opts.indexOpt.map(x => execute(x).as(ExitCode.Success))

  def makeIngestor: Resource[IO, ForumIngestor] =
    for
      config <- AppConfig.load.toResource
      res    <- AppResources.instance(config)
      forum  <- ForumIngestor(res.mongo, res.elastic, res.store, config.ingestor.forum).toResource
    yield forum

  def execute(config: IndexConfig): IO[Unit] =
    if config.index == Index.Forum then makeIngestor.use(_.run(config.since, config.until).compile.drain)
    else IO.raiseError(new RuntimeException("Only support forum for now"))

object opts:
  case class IndexConfig(index: Index, since: Instant, until: Option[Instant])

  // valid since <= until
  val indexOpt = (
    Opts.option[Index]("index", "Index to reindex", short = "i", metavar = "index"),
    Opts.option[Instant]("since", "Start date", short = "s", metavar = "time in epoch seconds"),
    Opts.option[Instant]("until", "End date", short = "u", metavar = "time in epoch seconds").orNone
  ).mapN(IndexConfig.apply)

  given Argument[Index] =
    Argument.from("index")(x => Validated.fromEither(Index.fromString(x)).toValidatedNel)

  given Argument[Instant] =
    Argument.from("time in epoch seconds")(str =>
      str.toLongOption.fold(Validated.invalidNel(s"Invalid epoch seconds: $str"))(x =>
        Validated.valid(Instant.ofEpochSecond(x))
      )
    )
