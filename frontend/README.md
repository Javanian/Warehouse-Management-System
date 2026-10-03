# StockFlow Frontend

Angular frontend for the StockFlow warehouse application. See the [project README](../README.md) for backend, database, authentication, and integration-test setup.

## Development

```bash
npm ci
npm start
```

The development UI runs on `http://localhost:4200` and proxies requests to the backend on port `8085`.

## Checks

```bash
npm run build
npm test -- --watch=false
```

These commands are provided for local use; they were not rerun during the documentation and history cleanup.
