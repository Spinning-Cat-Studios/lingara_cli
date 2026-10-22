package com.getlingara.cli.java.core;

/** One name and its value: a query parameter, a form field, a variable. Order is the caller's. */
public record Pair(String key, String value) {}
