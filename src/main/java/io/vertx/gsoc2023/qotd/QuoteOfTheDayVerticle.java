package io.vertx.gsoc2023.qotd;
import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
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
      router.route().handler(BodyHandler.create().setBodyLimit(1024 * 1024));

      router.get("/quotes").handler((ctx) -> {
        JsonArray quoteEntry = new JsonArray();
        pgPool.query("SELECT  text FROM quotes").execute(
          ar -> {
            if (ar.succeeded()) {
              ar.result().forEach(quote -> {
                quoteEntry.add(new JsonObject().put("quote", quote.getValue("text")));
              });
            } else{
              ctx.response().setStatusCode(500).end("query failed - " + ar.cause().getMessage());
            }
            ctx.response()
              .putHeader("content-type", "application/json")
              .setChunked(true)
              .end(quoteEntry.toBuffer());
          }
        );
      });

      router.post("/quotes").handler(ctx -> {
        JsonObject req = ctx.getBodyAsJson();
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
              ctx.response().end();
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
