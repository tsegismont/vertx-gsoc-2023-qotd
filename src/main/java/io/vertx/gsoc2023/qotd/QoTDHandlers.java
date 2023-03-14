package io.vertx.gsoc2023.qotd;

import io.vertx.core.Future;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import io.vertx.pgclient.PgPool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
import io.vertx.sqlclient.Tuple;

import static io.vertx.core.Future.failedFuture;

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

  public void postNewQuote(RoutingContext context) {
    var payload = context.body().asJsonObject();
    insertQuote(payload)
      .onFailure(cause -> context.response().setStatusCode(400).end())
      .onSuccess(__ -> context.response().setStatusCode(201).end());
  }

  private Future<Void> insertQuote(JsonObject quote) {
    if (quote.getString("text") == null)
      return failedFuture(new IllegalArgumentException("The 'text' field must be provided"));
    return pool
      .preparedQuery("INSERT INTO quotes (text, author) VALUES ($1, $2)")
      .execute(Tuple.of(quote.getString("text"), quote.getString("author", "Unknown")))
      .mapEmpty();
  }
}
