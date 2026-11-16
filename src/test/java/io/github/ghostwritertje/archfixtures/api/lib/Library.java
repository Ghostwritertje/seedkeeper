package io.github.ghostwritertje.archfixtures.api.lib;

import io.github.ghostwritertje.seedkeeper.ModuleApi;
import java.util.List;

/** The module API of {@code lib}: other modules may use it. */
@ModuleApi
public class Library {

    /** Book is a record in this public signature, and Shelf is one in Book's, so both may be used too. */
    public List<Book> books() {
        return List.of(new Book("Dune", Shelf.FICTION));
    }

    /** Not public, so Loose isn't in a public signature. */
    Loose loose() {
        return new Loose("not exported");
    }
}
