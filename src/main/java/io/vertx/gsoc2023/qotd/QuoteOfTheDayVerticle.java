package io.vertx.gsoc2023.qotd;

import java.util.ArrayList;
import java.util.List;

import io.vertx.config.ConfigRetriever;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;

public class QuoteOfTheDayVerticle extends AbstractVerticle {

  private PgPool pgPool;

  @Override
  public void start(Promise<Void> startFuture) throws Exception {
    ConfigRetriever retriever = ConfigRetriever.create(vertx);
    retriever.getConfig().compose(config -> {
      pgPool = setupPool(config);
      var router = setupRouter();
      var httpServer = createHttpServer(router);
      
      PgPool pool = setupPool(config);
      router.get("/quotes").handler(ctx -> getHandler(pool, ctx));
      
      return httpServer.listen(config.getInteger("httpPort", 8080)).<Void>mapEmpty();
    }).onComplete(startFuture);
  }
  
  private void getHandler(PgPool pool, RoutingContext ctx) {
		HttpServerResponse response = ctx.response();
		List<Quotes> receivedQuotes = new ArrayList<>();
		pool.getConnection().compose(conn -> {
			System.out.println("Got a connection from the pool");

			return conn.query("SELECT * from quotes").execute().onComplete(rows -> {
				for (Row row : rows.result()) {
					Quotes quote = new Quotes();
					quote.setId(row.getInteger(0));
					quote.setQuote(row.getString(1));
					quote.setAuthor(row.getString(2));
					receivedQuotes.add(quote);
				}
			}).eventually(x -> conn.close());
		}).onSuccess(x -> response.end(Json.encodePrettily(receivedQuotes)))
				.onFailure(v -> response.end(v.getCause().getLocalizedMessage()));
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
