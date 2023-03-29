package io.vertx.gsoc2023.qotd;

import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Tuple;

public class QuoteOfTheDayVerticle extends AbstractVerticle {

  private PgPool pgPool;
  private EventBus eventBus;
  private final String CONTENT_TYPE = "Content-Type";
  private final String JSON_MIME = "application/json";
  private final String REALTIME_ADDRESS = "realtime";

  @Override
  public void start(Promise<Void> startFuture) throws Exception {
    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(config -> {
      eventBus = vertx.eventBus();
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
    var router = Router.router(vertx);
    router.get("/quotes").handler(this::retrieveQuotes);
    router.post("/quotes").handler(BodyHandler.create()).handler(this::addQuote);
    router.get("/realtime").handler(this::realtimeQuotes);
    return router;
  }

  private void retrieveQuotes(RoutingContext context) {
    pgPool
      .query("SELECT quote_id, text, author FROM quotes")
      .execute()
      .map(rows -> {
        var response = new JsonArray();
        for (var quote: rows) {
          response.add(
            new JsonObject()
              .put("quote_id", quote.getValue("quote_id"))
              .put("text", quote.getValue("text"))
              .put("author", quote.getValue("author"))
          );
        }
        return response.encode();
      })
      .onSuccess(data -> context.response().putHeader(CONTENT_TYPE, JSON_MIME).end(data))
      .onFailure(throwable -> context.fail(500, throwable));
  }

  private void addQuote(RoutingContext context) {
    var request = context.body().asJsonObject();
    var author = request.getString("author", "Unknown");
    var text = request.getString("text");

    if (text == null) {
      context.fail(400);
    } else {
      pgPool
        .preparedQuery("INSERT INTO quotes (text, author) VALUES ($1, $2) RETURNING quote_id, text, author")
        .execute(Tuple.of(text, author))
        .map(rows -> {
          var row = rows.iterator().next();
          return new JsonObject()
            .put("quote_id", row.getValue("quote_id"))
            .put("text", row.getValue("text"))
            .put("author", row.getValue("author"));
        })
        .onSuccess(data -> {
          eventBus.publish(REALTIME_ADDRESS, data);
          context.response().putHeader(CONTENT_TYPE, JSON_MIME).end(data.encode());
        })
        .onFailure(throwable -> context.fail(500, throwable));
    }
  }

  private void realtimeQuotes(RoutingContext context) {
    context
      .request()
      .toWebSocket()
      .onSuccess(websocket -> {
        var consumer = eventBus.consumer(
          REALTIME_ADDRESS,
          (Message<JsonObject> message) -> websocket.write(message.body().toBuffer())
        );
        websocket.endHandler(ws -> consumer.unregister());
      });
  }

  private HttpServer createHttpServer(Router router) {
    return vertx.createHttpServer(new HttpServerOptions()).requestHandler(router);
  }
}
