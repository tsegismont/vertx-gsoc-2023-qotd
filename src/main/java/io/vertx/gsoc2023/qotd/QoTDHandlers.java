package io.vertx.gsoc2023.qotd;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.pgclient.PgPool;

public record QoTDHandlers(PgPool pool) {

  public void getAllQuotes(RoutingContext ctx) {
    fetchAllQuotes()
      .onFailure(Throwable::printStackTrace)
      .onSuccess(quotes -> ctx
        .response()
        .setStatusCode(200)
        .putHeader("content-type", "application/json")
        .end(quotes.encode()));
  }

  private Future<JsonArray> fetchAllQuotes() {
    return pool
      .query("SELECT * FROM quotes")
      .execute()
      .compose(rs -> {
        var jsonArray = new JsonArray();
        rs.forEach(row -> {
          var quote = new JsonObject()
            .put("quoteId", row.getLong("quote_id"))
            .put("text", row.getString("text"))
            .put("author", row.getString("author"));
          jsonArray.add(quote);
        });
        return Future.succeededFuture(jsonArray);
      });
  }
}
