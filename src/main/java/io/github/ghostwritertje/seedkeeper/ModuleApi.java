package io.github.ghostwritertje.seedkeeper;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a type of a module that other modules may use (GLOSSARY.md, Module API). Records and enums in the public
 * signatures of a module API type may be used too, without the mark. A module has at most 3 module API types that
 * aren't records or enums. {@code ModuleRulesTest} enforces both.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.TYPE)
public @interface ModuleApi {}
