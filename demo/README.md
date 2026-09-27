# nlq-demo

A generic, config-driven demo app for [hyperphor/nlq](https://github.com/hyperphor/nlq)
— point it at a database and launch, no code required. See `../design/demo-app.md`.

## Setup

```
ln -s ../.. checkouts/nlq   # picks up this repo's own live src/ instead of a published jar
npm install
```

Edit `resources/config.edn`'s `:nlq` entry to point at your own database (see that
file's header comment for the shape), then:

```
lein run 8080
```

opens a browser tab with a project picker, NL query box, results grid, and (if any
configured project declares a `:schema`) a Schema tab.
