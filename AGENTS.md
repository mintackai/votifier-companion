# AGENTS.md

## Javadoc

Write a javadoc comment on every public method whose behavior is not self-explanatory from its name and signature.
Skip it for methods that are obvious, such as plain getters.

Use javadoc tags where they add information: `@param` for parameters with constraints or non-obvious meaning,
`@return` for what is returned, and `@throws` for exceptions a caller should expect.
