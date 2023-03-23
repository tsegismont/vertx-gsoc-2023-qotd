package io.vertx.gsoc2023.qotd;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.MessageConsumer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;


public class QuoteOfTheDayVerticle extends AbstractVerticle {

  private PgPool pgPool;



  @Override
  public void start(Promise<Void> startFuture) throws Exception {
    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(config -> {
      pgPool = setupPool(config);
      var router = setupRouter();
      EventBus eb = vertx.eventBus();
      MessageConsumer<JsonArray> consumer = eb.consumer("realtime-feedback");

      router.get("/quotes").handler((ctx) -> {
        JsonArray quoteEntry = new JsonArray();
        pgPool.query("SELECT  text FROM quotes").execute(
          ar -> {
            if (ar.succeeded())
              ar.result().forEach(quote -> {
                quoteEntry.add(
                  new JsonObject()
                    .put("quote", quote.getValue("text")));
              });
             else
              ctx.response().setStatusCode(500)
                .end("query failed - " + ar.cause().getMessage());

            ctx.response()
              .putHeader("content-type", "application/json")
              .setChunked(true)
              .end(quoteEntry.toBuffer());
          }
        );
      });

      router.post("/quotes")
        .handler(BodyHandler.create().setBodyLimit(1024 * 1024))
        .handler(ctx -> {
        JsonObject req = ctx.body().asJsonObject();
        String author = req.getString("author", "unknown");
        String quote = req.getString("text");
        if(quote == null)
          ctx.response()
            .setStatusCode(400)
            .putHeader("content-type", "application/json")
            .end((new JsonObject().put("error", "quote is not supplied")).toBuffer());
        pgPool.preparedQuery("INSERT into quotes (author, text) VALUES ($1, $2)")
          .execute(Tuple.of(author, quote), ar -> {
            if(ar.succeeded())
              ctx.response().end(); //The Default status code is always 200 on success;
          });
      });


      /*
      * The route uses a  consumer handler
      * to immediately publish or push messages
      * (json serialized objects) on to the
      * event bus when a messagee becomes available,
      * so that for example all post request
      * to the websocket to update or modify data can immediately
      * be seen by all other consumer handlers subscribed to
      * websocket (e.g GET requests)
      * */
      router.get("/realtime").handler(ctx ->{
        ctx.request().toWebSocket()
          .onSuccess(serverWebSocket -> {
          consumer.handler(message -> {
            serverWebSocket.writeBinaryMessage(message.body().toBuffer());
          });
          serverWebSocket.close(rr -> consumer.unregister());
        })
          .onFailure( failure -> {
            ctx.response()
              .setStatusCode(500)
              .putHeader("content-type", "application/json")
              .end((new JsonObject().put("error", failure.getMessage()).toBuffer()));
          });
      });


      var httpServer = createHttpServer(router);

      return httpServer.listen(config.getInteger("httpPort", 8080)).<Void>mapEmpty();
    }).onComplete(startFuture);
  }

  private PgPool setupPool(JsonObject config) {
    PgConnectOptions connectOptions = new PgConnectOptions()
      .setUser(config.getString("dbUser", "quotes"))
      .setPassword(config.getString("dbPassword", "super$ecret"))
      .setDatabase(config.getString("dbName", "quotes"))
      .setHost(config.getString("dbHost", "localhost"))
      .setPort(config.getInteger("dbPort", 5432));
    return PgPool.pool(vertx, connectOptions, new PoolOptions());
  }

  private Router setupRouter() {
    return Router.router(vertx);
  }

  private HttpServer createHttpServer(Router router) {
    return vertx.createHttpServer(new HttpServerOptions()).requestHandler(router);
  }
}
