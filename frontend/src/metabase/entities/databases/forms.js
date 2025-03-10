import React from "react";
import { t, jt } from "ttag";

import MetabaseSettings from "metabase/lib/settings";
import { getElevatedEngines } from "metabase/lib/engine";
import ExternalLink from "metabase/components/ExternalLink";
import { PLUGIN_CACHING } from "metabase/plugins";
import getFieldsForBigQuery from "./big-query-fields";

import getFieldsForMongo from "./mongo-fields";
import MetadataSyncScheduleWidget from "metabase/admin/databases/components/widgets/MetadataSyncScheduleWidget";
import CacheFieldValuesScheduleWidget from "metabase/admin/databases/components/widgets/CacheFieldValuesScheduleWidget";
import EngineWidget from "metabase/admin/databases/components/widgets/EngineWidget";

const DATABASE_DETAIL_OVERRIDES = {
  "tunnel-enabled": () => ({
    title: t`Use an SSH-tunnel for database connections`,
    description: t`Some database installations can only be accessed by connecting through an SSH bastion host. This option also provides an extra layer of security when a VPN is not available. Enabling this is usually slower than a direct connection.`,
  }),
  "use-jvm-timezone": () => ({
    title: t`Use the Java Virtual Machine (JVM) timezone`,
    description: t`We suggest you leave this off unless you're doing manual timezone casting in many or most of your queries with this data.`,
  }),
  "include-user-id-and-hash": () => ({
    title: t`Include User ID and query hash in queries`,
    description: t`When on, Metabase User ID and query hash get appended to queries on this database, which can be useful for auditing and debugging. However, this causes each query to look distinct, preventing BigQuery from returning cached results, which may increase your costs.`,
  }),
  "use-srv": () => ({
    title: t`Use DNS SRV when connecting`,
    description: t`Using this option requires that provided host is a FQDN.  If connecting to an Atlas cluster, you might need to enable this option.  If you don't know what this means, leave this disabled.`,
  }),
  "client-id": (engine, details) => ({
    description: getClientIdDescription(engine, details),
  }),
  "auth-code": (engine, details) => ({
    description: (
      <div>
        <div>{getAuthCodeLink(engine, details)}</div>
        <div>{getAuthCodeEnableAPILink(engine, details)}</div>
      </div>
    ),
  }),
  "service-account-json": (engine, details, id) => ({
    validate: value => {
      // this field is only required if this is a new entry
      if (id) {
        return null;
      }

      if (!value) {
        return t`required`;
      }
      try {
        JSON.parse(value);
      } catch (e) {
        return t`invalid JSON`;
      }
      return null;
    },
  }),
  "tunnel-private-key": () => ({
    title: t`SSH private key`,
    placeholder: t`Paste the contents of your ssh private key here`,
    type: "text",
  }),
  "tunnel-private-key-passphrase": () => ({
    title: t`Passphrase for the SSH private key`,
  }),
  "tunnel-auth-option": () => ({
    title: t`SSH Authentication`,
    options: [
      { name: t`SSH Key`, value: "ssh-key" },
      { name: t`Password`, value: "password" },
    ],
  }),
  "ssl-cert": () => ({
    title: t`Server SSL certificate chain`,
    placeholder: t`Paste the contents of the server's SSL certificate chain here`,
    type: "text",
  }),
};

const AUTH_URL_PREFIXES = {
  bigquery:
    "https://accounts.google.com/o/oauth2/auth?redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=https://www.googleapis.com/auth/bigquery&client_id=",
  bigquery_with_drive:
    "https://accounts.google.com/o/oauth2/auth?redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=https://www.googleapis.com/auth/bigquery%20https://www.googleapis.com/auth/drive&client_id=",
  googleanalytics:
    "https://accounts.google.com/o/oauth2/auth?access_type=offline&redirect_uri=urn:ietf:wg:oauth:2.0:oob&response_type=code&scope=https://www.googleapis.com/auth/analytics.readonly&client_id=",
};

const ENABLE_API_PREFIXES = {
  googleanalytics:
    "https://console.developers.google.com/apis/api/analytics.googleapis.com/overview?project=",
};

const CREDENTIALS_URL_PREFIXES = {
  bigquery:
    "https://console.developers.google.com/apis/credentials/oauthclient?project=",
  googleanalytics:
    "https://console.developers.google.com/apis/credentials/oauthclient?project=",
};

export const DEFAULT_SCHEDULES = {
  cache_field_values: {
    schedule_day: null,
    schedule_frame: null,
    schedule_hour: 0,
    schedule_type: "daily",
  },
  metadata_sync: {
    schedule_day: null,
    schedule_frame: null,
    schedule_hour: null,
    schedule_type: "hourly",
  },
};

function concatTrimmed(a, b) {
  return (a || "").trim() + (b || "").trim();
}

function getClientIdDescription(engine, details) {
  if (CREDENTIALS_URL_PREFIXES[engine]) {
    const credentialsURL = concatTrimmed(
      CREDENTIALS_URL_PREFIXES[engine],
      details["project-id"] || "",
    );
    return (
      <span>
        {jt`${(
          <ExternalLink className="link" href={credentialsURL}>
            {t`Click here`}
          </ExternalLink>
        )} to generate a Client ID and Client Secret for your project.`}{" "}
        {t`Choose "Desktop App" as the application type. Name it whatever you'd like.`}
      </span>
    );
  }
}

function getAuthCodeLink(engine, details) {
  if (AUTH_URL_PREFIXES[engine] && details["client-id"]) {
    const authCodeURL = concatTrimmed(
      AUTH_URL_PREFIXES[engine],
      details["client-id"],
    );
    const googleDriveAuthCodeURL = concatTrimmed(
      AUTH_URL_PREFIXES["bigquery_with_drive"],
      details["client-id"],
    );
    return (
      <span>
        {jt`${(
          <ExternalLink href={authCodeURL}>{t`Click here`}</ExternalLink>
        )} to get an auth code.`}
        {engine === "bigquery" && (
          <span>
            {" "}
            ({t`or`}{" "}
            <ExternalLink href={googleDriveAuthCodeURL}>
              {t`with Google Drive permissions`}
            </ExternalLink>
            )
          </span>
        )}
      </span>
    );
  }
}
function getAuthCodeEnableAPILink(engine, details) {
  // for Google Analytics we need to show a link for people to go to the Console to enable the GA API
  if (AUTH_URL_PREFIXES[engine] && details["client-id"]) {
    // projectID is just the first numeric part of the client-id.
    // e.g. client-id might be 123436115855-q8z42hilmjf8iplnnu49n7jbudmxxdf.apps.googleusercontent.com
    // then project-id would be 123436115855
    const projectID =
      details["client-id"] && (details["client-id"].match(/^\d+/) || [])[0];
    if (ENABLE_API_PREFIXES[engine] && projectID) {
      // URL looks like https://console.developers.google.com/apis/api/analytics.googleapis.com/overview?project=12343611585
      const enableAPIURL = concatTrimmed(
        ENABLE_API_PREFIXES[engine],
        projectID,
      );

      return (
        <span>
          {t`To use Metabase with this data you must enable API access in the Google Developers Console.`}{" "}
          {jt`${(
            <ExternalLink href={enableAPIURL}>{t`Click here`}</ExternalLink>
          )} to go to the console if you haven't already done so.`}
        </span>
      );
    }
  }
}

function getEngineInfo(engine, details, id) {
  const engineInfo = (MetabaseSettings.get("engines") || {})[engine];
  switch (engine) {
    // BigQuery has special logic to switch out forms depending on what style of authenication we use.
    case "bigquery":
      return getFieldsForBigQuery(details);
    // Mongo has special logic to switch between a connection URI and broken out fields
    case "mongo":
      return getFieldsForMongo(details, engineInfo, id);
    default:
      return engineInfo;
  }
}

function shouldShowEngineProvidedField(field, details) {
  const detailAndValueRequiredToShowField = field["visible-if"];

  if (detailAndValueRequiredToShowField) {
    const [detail, expectedDetailValue] = Object.entries(
      detailAndValueRequiredToShowField,
    )[0];

    return details[detail] === expectedDetailValue;
  }

  return true;
}

function getDefaultValue(field) {
  return "default" in field ? field.default : null;
}

function normalizeFieldValue(value, field) {
  if (value === "" || value == null) {
    return getDefaultValue(field);
  }

  if (typeof value === "string" && field.type !== "password") {
    const trimmedValue = value.trim();
    return trimmedValue === "" ? getDefaultValue(field) : trimmedValue;
  }

  return value;
}

function getEngineFormFields(engine, details, id) {
  const engineInfo = getEngineInfo(engine, details, id);
  const engineFields = engineInfo ? engineInfo["details-fields"] : [];

  // convert database details-fields to Form fields
  return engineFields
    .filter(field => shouldShowEngineProvidedField(field, details))
    .map(field => {
      const overrides = DATABASE_DETAIL_OVERRIDES[field.name];

      return {
        name: `details.${field.name}`,
        title: field["display-name"],
        type: field.type,
        description: field.description,
        placeholder: field.placeholder || field.default,
        options: field.options,
        validate: value => (field.required && !value ? t`required` : null),
        normalize: value => normalizeFieldValue(value, field),
        horizontal: field.type === "boolean",
        initial: field.default,
        readOnly: field.readOnly || false,
        ...(overrides && overrides(engine, details, id)),
      };
    });
}

const ENGINES = MetabaseSettings.get("engines", {});
const ELEVATED_ENGINES = getElevatedEngines();

const ENGINE_OPTIONS = Object.entries(ENGINES)
  .map(([engine, info]) => ({
    value: engine,
    name: info["driver-name"],
    official: info["official"] ?? true, // TODO remove default
    index: ELEVATED_ENGINES.indexOf(engine),
  }))
  .sort((a, b) => a.name.localeCompare(b.name));

// use top level constant for engines so we only need to compute these maps once
const ENGINE_SUPERSEDES_MAPS = Object.keys(ENGINES).reduce(
  (acc, engine) => {
    const newEngine = ENGINES[engine]["superseded-by"];
    if (newEngine) {
      acc.supersedes[newEngine] = engine;
      acc.superseded_by[engine] = newEngine;
    }
    return acc;
  },
  { supersedes: {}, superseded_by: {} },
);

/**
 * Returns the options to show in the engines selection widget. An engine is available to be selected if either
 *  - it is not superseded by any other engine
 *  - it is the selected engine (i.e. someone is already using it)
 *  - it is superseded by some engine, which happens to be the currently selected one
 *
 * The idea behind this behavior is to only show someone a "legacy" driver if they have at least selected the one that
 * will replace it first, at which point they can "fall back" on the legacy one if needed.
 *
 * @param currentEngine the current (selected engine)
 * @returns the filtered engine options to be shown in the selection widget
 */
function getEngineOptions(currentEngine) {
  return ENGINE_OPTIONS.filter(engine => {
    const engineName = engine.value;
    const newDriver = ENGINE_SUPERSEDES_MAPS["superseded_by"][engineName];
    return (
      typeof newDriver === "undefined" ||
      newDriver === currentEngine ||
      engineName === currentEngine
    );
  });
}

function getDatabaseCachingField() {
  const hasField =
    PLUGIN_CACHING.databaseCacheTTLFormField &&
    MetabaseSettings.get("enable-query-caching");
  return hasField ? PLUGIN_CACHING.databaseCacheTTLFormField : null;
}

const forms = {
  details: {
    fields: ({ id, engine, details = {} } = {}) =>
      [
        {
          name: "engine",
          title: t`Database type`,
          type: "select",
          options: getEngineOptions(engine),
          placeholder: t`Select a database`,
          isHosted: MetabaseSettings.isHosted(),
        },
        {
          name: "name",
          title: t`Name`,
          placeholder: t`How would you like to refer to this database?`,
          validate: value => !value && t`required`,
          hidden: !engine,
        },
        ...(getEngineFormFields(engine, details, id) || []),
        {
          name: "auto_run_queries",
          type: "boolean",
          title: t`Automatically run queries when doing simple filtering and summarizing`,
          description: t`When this is on, Metabase will automatically run queries when users do simple explorations with the Summarize and Filter buttons when viewing a table or chart. You can turn this off if querying this database is slow. This setting doesn’t affect drill-throughs or SQL queries.`,
          hidden: !engine,
        },
        {
          name: "details.let-user-control-scheduling",
          type: "boolean",
          title: t`This is a large database, so let me choose when Metabase syncs and scans`,
          description: t`By default, Metabase does a lightweight hourly sync and an intensive daily scan of field values. If you have a large database, we recommend turning this on and reviewing when and how often the field value scans happen.`,
          hidden: !engine,
        },
        {
          name: "refingerprint",
          type: "boolean",
          title: t`Periodically refingerprint tables`,
          description: t`When syncing with this database, Metabase will scan a subset of values of fields to gather statistics that enable things like improved binning behavior in charts, and to generally make your Metabase instance smarter.`,
          hidden: !engine,
        },
        getDatabaseCachingField(),
        { name: "is_full_sync", type: "hidden" },
        { name: "is_on_demand", type: "hidden" },
        {
          name: "schedules.metadata_sync",
          type: MetadataSyncScheduleWidget,
          title: t`Database syncing`,
          description: t`This is a lightweight process that checks for updates to this database’s schema. In most cases, you should be fine leaving this set to sync hourly.`,
          hidden: !engine || !details["let-user-control-scheduling"],
        },
        {
          name: "schedules.cache_field_values",
          type: CacheFieldValuesScheduleWidget,
          title: t`Scanning for Filter Values`,
          description:
            t`Metabase can scan the values present in each field in this database to enable checkbox filters in dashboards and questions. This can be a somewhat resource-intensive process, particularly if you have a very large database.` +
            " " +
            t`When should Metabase automatically scan and cache field values?`,
          hidden: !engine || !details["let-user-control-scheduling"],
        },
      ].filter(Boolean),
    normalize: function(database) {
      if (!database.details["let-user-control-scheduling"]) {
        // TODO Atte Keinänen 8/15/17: Implement engine-specific scheduling defaults
        return {
          ...database,
          is_full_sync: true,
        };
      } else {
        return database;
      }
    },
  },
};

forms.setup = {
  ...forms.details,
  fields: (...args) =>
    forms.details.fields(...args).map(field => ({
      ...field,
      type: field.name === "engine" ? EngineWidget : field.type,
      title: field.name === "engine" ? null : field.title,
      hidden: field.hidden || SCHEDULING_FIELDS.has(field.name),
    })),
};

// partial forms for tabbed view:
forms.connection = {
  ...forms.details,
  fields: (...args) =>
    forms.details.fields(...args).map(field => ({
      ...field,
      hidden: field.hidden || SCHEDULING_FIELDS.has(field.name),
    })),
};
forms.scheduling = {
  ...forms.details,
  fields: (...args) =>
    forms.details.fields(...args).map(field => ({
      ...field,
      hidden: field.hidden || !SCHEDULING_FIELDS.has(field.name),
    })),
};

const SCHEDULING_FIELDS = new Set([
  "schedules.metadata_sync",
  "schedules.cache_field_values",
]);

export default forms;
export const engineSupersedesMap = ENGINE_SUPERSEDES_MAPS;
export const allEngines = ENGINES;
