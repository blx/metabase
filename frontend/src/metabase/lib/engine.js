import { formatSQL } from "metabase/lib/formatting";

export function getEngineNativeType(engine) {
  switch (engine) {
    case "mongo":
    case "druid":
    case "googleanalytics":
      return "json";
    default:
      return "sql";
  }
}

export function getEngineNativeAceMode(engine) {
  switch (engine) {
    case "mongo":
    case "druid":
    case "googleanalytics":
      return "ace/mode/json";
    case "mysql":
      return "ace/mode/mysql";
    case "postgres":
      return "ace/mode/pgsql";
    case "sqlserver":
      return "ace/mode/sqlserver";
    default:
      return "ace/mode/sql";
  }
}

export function getEngineLogo(engine) {
  const path = `/app/assets/img/drivers`;

  switch (engine) {
    case "bigquery":
    case "druid":
    case "googleanalytics":
    case "h2":
    case "mongo":
    case "mysql":
    case "oracle":
    case "postgres":
    case "presto":
    case "redshift":
    case "snowflake":
    case "sparksql":
    case "sqlite":
    case "sqlserver":
    case "vertica":
      return `${path}/${engine}.svg`;
    case "bigquery-cloud-sdk":
      return `${path}/bigquery.svg`;
    case "presto-jdbc":
      return `${path}/presto.svg`;
  }
}

export function getElevatedEngines() {
  return [
    "mysql",
    "postgres",
    "sqlserver",
    "redshift",
    "bigquery-cloud-sdk",
    "snowflake",
  ];
}

export function getEngineNativeRequiresTable(engine) {
  return engine === "mongo";
}

export function getEngineSupportsFirewall(engine) {
  return engine !== "googleanalytics";
}

export function formatJsonQuery(query, engine) {
  if (engine === "googleanalytics") {
    return formatGAQuery(query);
  } else {
    return JSON.stringify(query);
  }
}

export function formatNativeQuery(query, engine) {
  return getEngineNativeType(engine) === "json"
    ? formatJsonQuery(query, engine)
    : formatSQL(query);
}

const GA_ORDERED_PARAMS = [
  "ids",
  "start-date",
  "end-date",
  "metrics",
  "dimensions",
  "sort",
  "filters",
  "segment",
  "samplingLevel",
  "include-empty-rows",
  "start-index",
  "max-results",
];

// does 3 things: removes null values, sorts the keys by the order in the documentation, and formats with 2 space indents
function formatGAQuery(query) {
  if (!query) {
    return "";
  }
  const object = {};
  for (const param of GA_ORDERED_PARAMS) {
    if (query[param] != null) {
      object[param] = query[param];
    }
  }
  return JSON.stringify(object, null, 2);
}
