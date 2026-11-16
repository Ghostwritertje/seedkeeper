package io.github.ghostwritertje.archfixtures.domain.web;

import jakarta.ws.rs.Path;

/** Not a domain module, so rule 4 lets it depend on JAX-RS. */
@Path("/fixture")
public class Endpoint {}
