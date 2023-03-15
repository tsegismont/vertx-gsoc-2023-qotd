package io.vertx.gsoc2023.qotd;

import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

public class QuoteOfTheDayVerticle extends AbstractVerticle {

  private PgPool pgPool;

  @Override
  public void start(Promise<Void> startFuture) throws Exception {
    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(config -> {
      pgPool = setupPool(config);
      var router = setupRouter();
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
    Router router = Router.router(vertx);
    final String REALTIME_EVENT_ADDRESS = "realtime-quote";

    router.route().failureHandler(ctx -> {
      ctx.response()
        .setStatusCode(HttpResponseStatus.INTERNAL_SERVER_ERROR.code())
        .end("Failure: " + ctx.failure().getMessage());
    });

    router.get("/quotes").handler(ctx -> {
      selectQuotes().compose(rows -> {
        JsonArray jsonArray = new JsonArray();
        rows.forEach(row -> jsonArray.add(row.toJson()));

        return ctx.response()
          .putHeader(HttpHeaderNames.CONTENT_TYPE, "application/json")
          .end(jsonArray.encodePrettily());
      }).onFailure(cause -> ctx.fail(cause));
    });

    router.post("/quotes").handler(ctx -> {
      ctx.request()
        .body()
        .compose(buff -> {
          JsonObject jsonObject = buff.toJsonObject();
  
          if (jsonObject.getString("text") == null) {
            return ctx.response()
              .setStatusCode(HttpResponseStatus.BAD_REQUEST.code())
              .end("Quote text must be provided in payload");
          }
  
          return insertQuoteAndReturn(jsonObject.getString("author", "Unknown"), jsonObject.getString("text"))
              .compose(rows -> {
                JsonObject insertedQuote = rows.iterator().next().toJson();

                ctx.vertx()
                  .eventBus()
                  .publish(REALTIME_EVENT_ADDRESS, insertedQuote, new DeliveryOptions().setLocalOnly(true));
              
                return ctx.response()
                  .setStatusCode(HttpResponseStatus.CREATED.code())
                  .putHeader(HttpHeaderNames.CONTENT_TYPE, "application/json")
                  .end(insertedQuote.encodePrettily());
              });
        }).onFailure(cause -> ctx.fail(cause));
    });

    router.get("/realtime").handler(ctx -> {
      ctx.request()
        .toWebSocket()
        .onSuccess(webSocket -> {
          ctx.vertx()
            .eventBus()
            .localConsumer(REALTIME_EVENT_ADDRESS, message -> {
              webSocket.writeTextMessage(message.body().toString());
            });
        }).onFailure(cause -> ctx.fail(cause));
    });


    return router;
  }

  private HttpServer createHttpServer(Router router) {
    return vertx.createHttpServer(new HttpServerOptions()).requestHandler(router);
  }
  
  private Future<RowSet<Row>> selectQuotes() {
    return pgPool.withConnection(connection -> 
      connection
      .query("SELECT * FROM quotes ")
      .execute());
  }
  
  private Future<RowSet<Row>> insertQuoteAndReturn(String author, String text) {
    return pgPool.withConnection(connection -> 
      connection
      .preparedQuery("INSERT INTO quotes (author, text) VALUES ($1, $2) RETURNING * ")
      .execute(Tuple.of(author, text)));
  }
}
