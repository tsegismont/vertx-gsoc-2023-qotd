package io.vertx.gsoc2023.qotd;

import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.impl.logging.Logger;
import io.vertx.core.impl.logging.LoggerFactory;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.BodyHandler;
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
    var router = Router.router(vertx);
    setupGet(router);
    setupPost(router);
    return router;
  }

  private void setupPost(Router router) {
    router.post("/quotes")
      .consumes("application/json")
      .handler(BodyHandler.create())
      .handler(ctx -> {
        JsonObject body = ctx.body().asJsonObject();
        if (body != null && body.containsKey("text")) {
          pgPool.preparedQuery("INSERT INTO quotes (text, author) VALUES ($1, $2) RETURNING *")
            .execute(Tuple.of(body.getString("text"), body.getString("author", "Unknown")),
              ar -> {
                if (ar.succeeded()) {
                  var result = ar.result();
                  Row row = result.iterator().next();
                  ctx.json(row.toJson());
                } else {
                  ctx.fail(ar.cause());
                }
              });
        } else {
          ctx.fail(400);
        }
      });
  }

  private void setupGet(Router router) {
    router.get("/quotes").respond(ctx ->
      pgPool.query("SELECT * from quotes").execute()
        .map(rowSet -> {
          JsonArray fetchedQuotes = new JsonArray();
          rowSet.forEach(row -> fetchedQuotes.add(row.toJson()));
          return fetchedQuotes;
        }));
  }

  private HttpServer createHttpServer(Router router) {
    return vertx.createHttpServer(new HttpServerOptions()).requestHandler(router);
  }

}
