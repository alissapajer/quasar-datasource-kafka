/*
 * Copyright 2020 Precog Data
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.datasource.kafka

import slamdata.Predef._

import cats.data.NonEmptyList
import cats.effect._
import cats.syntax.applicative._
import cats.syntax.option._
import eu.timepit.refined.auto._
import fs2.Stream
import fs2.kafka._
import quasar.api.datasource.DatasourceType
import quasar.api.resource.ResourcePath.Root
import quasar.api.resource.{ResourceName, ResourcePath, ResourcePathType}
import quasar.connector.datasource.{BatchLoader, LightweightDatasource, Loader}
import quasar.connector.{MonadResourceErr, QueryResult, ResourceError}
import quasar.contrib.scalaz.MonadError_
import quasar.qscript.InterpretedRead

final class KafkaDatasource[F[_]: ConcurrentEffect: ContextShift: Timer: MonadResourceErr, K, V](
    config: Config,
    consumerSettings: ConsumerSettings[F, K, V],
    decoder: RecordDecoder[F, K, V])
    extends LightweightDatasource[Resource[F, ?], Stream[F, ?], QueryResult[F]]  {

  override def kind: DatasourceType = DatasourceType("kafka", 1L)

  override def loaders: NonEmptyList[Loader[Resource[F, *], InterpretedRead[ResourcePath], QueryResult[F]]] =
    NonEmptyList.of(Loader.Batch(BatchLoader.Full { (iRead: InterpretedRead[ResourcePath]) =>
      iRead.path.unsnoc match {
        case Some(Root -> ResourceName(topic)) if config.isTopic(topic) =>
          val F: ConcurrentEffect[F] = ConcurrentEffect[F]
          val stream = for {
            groupId <- Resource.liftF(F.delay(f"${config.groupId}%s_${randomUuid().toString}%s"))
            settings = consumerSettings.withGroupId(groupId)
            bytes <- Consumer(settings, decoder).fetch(topic)
          } yield bytes
          stream.map(bytes => QueryResult.typed(config.format, bytes, iRead.stages))
        case _                                                          =>
          Resource.liftF(MonadError_[F, ResourceError].raiseError[QueryResult[F]](ResourceError.NotAResource(iRead.path)))
      }
    }))


  override def pathIsResource(path: ResourcePath): Resource[F, Boolean] =
    Resource.liftF(path.unsnoc match {
      case Some(Root -> ResourceName(topic)) =>
        config.isTopic(topic).pure[F]
      case _ =>
        false.pure[F]
    })

  override def prefixedChildPaths(prefixPath: ResourcePath)
      : Resource[F, Option[Stream[F, (ResourceName, ResourcePathType.Physical)]]] =
    pathIsResource(prefixPath) evalMap {
      case true =>
        Stream.empty
          .covaryOutput[(ResourceName, ResourcePathType.Physical)]
          .covary[F].some.pure[F]
      case false =>
        prefixPath match {
          case Root =>
            Stream(config.topics.toList: _*)
              .map(ResourceName(_) -> ResourcePathType.leafResource)
              .covaryOutput[(ResourceName, ResourcePathType.Physical)]
              .covary[F].some.pure[F]
          case _    =>
            none[Stream[F, (ResourceName, ResourcePathType.Physical)]].pure[F]
        }
    }
}

object KafkaDatasource {
  def apply[F[_]: ConcurrentEffect: ContextShift: Timer: MonadResourceErr, K, V](
      config: Config,
      consumerSettings: ConsumerSettings[F, K, V],
      decoder: RecordDecoder[F, K, V])
      : KafkaDatasource[F, K, V] =
    new KafkaDatasource(config, consumerSettings, decoder)
}
