package io.github.ghostwritertje.archfixtures.api.app;

import io.github.ghostwritertje.archfixtures.api.fresh.Internal;
import io.github.ghostwritertje.archfixtures.api.lib.Book;
import io.github.ghostwritertje.archfixtures.api.lib.Hidden;
import io.github.ghostwritertje.archfixtures.api.lib.Library;
import io.github.ghostwritertje.archfixtures.api.lib.Loose;
import io.github.ghostwritertje.archfixtures.api.lib.Shelf;

/**
 * Breaks rule 2 three times, by using Hidden and Loose of module {@code lib} and Internal of module {@code fresh};
 * everything else it uses is allowed.
 */
public class Reader {

    public String read(Library library, Hidden hidden, Loose loose, Internal internal) {
        Book first = library.books().getFirst();
        Shelf shelf = first.shelf();
        return first.title() + shelf + hidden.secret() + loose.text() + internal.value();
    }
}
