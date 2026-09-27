# nlq-demo

A generic, config-driven demo app for [hyperphor/nlq](https://github.com/hyperphor/nlq)
— point it at a database and launch, no code required. See `../design/demo-app.md`.

## Setup

```
ln -s ../.. checkouts/nlq   # required, not optional -- see below
npm install
```

The `checkouts/nlq` symlink is **required**, not a nice-to-have: it puts this repo's
own live `src/` ahead of the pinned `com.hyperphor/nlq` jar on the classpath.
Without it, the seeded `resources/config.edn` (a `:provider :sqlite` project) fails
at boot with `Could not locate hyperphor/nlq/sources/sqlite.clj on classpath` --
SQLite support hasn't shipped in a released `nlq` jar yet. Once it has, the symlink
becomes optional (only needed to test unreleased library changes); until then, skip
it only if you've edited `resources/config.edn` to use a provider (`:postgres`,
`:bigquery`, `:cirro`) that *is* in the pinned release.

Edit `resources/config.edn`'s `:nlq` entry to point at your own database (see that
file's header comment for the shape), then:

```
lein run 8080
```

opens a browser tab with a project picker, NL query box, results grid, and (if any
configured project declares a `:schema`) a Schema tab.
