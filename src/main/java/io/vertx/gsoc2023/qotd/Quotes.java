package io.vertx.gsoc2023.qotd;

public class Quotes {
	private int id;
	private String quote;
	private String author;

	public Quotes() {

	}

	public Quotes(int id, String quote, String author) {
		this.id = id;
		this.quote = quote;
		this.author = author;
	}

	public int getId() {
		return id;
	}

	public void setId(int id) {
		this.id = id;
	}

	public String getQuote() {
		return quote;
	}

	public void setQuote(String quote) {
		this.quote = quote;
	}

	public String getAuthor() {
		return author;
	}

	public void setAuthor(String author) {
		this.author = author;
	}

}
