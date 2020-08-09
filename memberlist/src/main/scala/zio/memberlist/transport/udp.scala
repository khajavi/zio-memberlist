package zio.memberlist.transport

import zio._
import zio.clock.Clock
import zio.memberlist.TransportError
import zio.memberlist.TransportError._
import zio.logging.Logging
import zio.logging.log
import zio.nio.channels.{ Channel => _, _ }
import zio.nio.core.{ Buffer, SocketAddress }

object udp {

  /**
   * Creates udp transport with given maximum message size.
   * @param mtu - maximum message size
   * @return layer with Udp transport
   */
  def live(mtu: Int): ZLayer[Clock with Logging, Nothing, ConnectionLessTransport] =
    ZLayer.fromFunction { env =>
      new ConnectionLessTransport.Service {
        def bind(addr: SocketAddress)(connectionHandler: Channel => UIO[Unit]): Managed[TransportError, Bind] =
          DatagramChannel
            .bind(Some(addr))
            .mapError(BindFailed(addr, _))
            .withEarlyRelease
            .onExit { _ =>
              log.info("shutting down server")
            }
            .mapM {
              case (close, server) =>
                Buffer
                  .byte(mtu)
                  .flatMap(buffer =>
                    server
                      .receive(buffer)
                      .mapError(ExceptionWrapper)
                      .tap(_ => buffer.flip)
                      .map {
                        case Some(addr) =>
                          new Channel(
                            bytes => buffer.getChunk(bytes).mapError(ExceptionWrapper),
                            chunk => Buffer.byte(chunk).flatMap(server.send(_, addr)).mapError(ExceptionWrapper).unit,
                            ZIO.succeed(true),
                            ZIO.unit
                          )
                        case None =>
                          new Channel(
                            bytes => buffer.flip.flatMap(_ => buffer.getChunk(bytes)).mapError(ExceptionWrapper),
                            _ => ZIO.fail(new RuntimeException("Cannot reply")).mapError(ExceptionWrapper).unit,
                            ZIO.succeed(true),
                            ZIO.unit
                          )
                      }
                      .flatMap(
                        connectionHandler
                      )
                  )
                  .forever
                  .fork
                  .as {
                    val local = server.localAddress
                      .flatMap(opt => IO.effect(opt.get).orDie)
                      .mapError(ExceptionWrapper(_))
                    new Bind(server.isOpen, close.unit, local)
                  }
            }
            .provide(env)

        def connect(to: SocketAddress): Managed[TransportError, Channel] =
          DatagramChannel
            .connect(to)
            .mapM(channel =>
              Channel.withLock(
                channel.read(_).mapError(ExceptionWrapper),
                channel.write(_).mapError(ExceptionWrapper).unit,
                ZIO.succeed(true),
                ZIO.unit
              )
            )
            .mapError(ExceptionWrapper)
      }
    }
}
