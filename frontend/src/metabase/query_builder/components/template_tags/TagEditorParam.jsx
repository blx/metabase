/* eslint-disable react/prop-types */
import React, { Component } from "react";
import { t } from "ttag";
import _ from "underscore";
import { connect } from "react-redux";
import { Link } from "react-router";

import Toggle from "metabase/components/Toggle";
import InputBlurChange from "metabase/components/InputBlurChange";
import Select, { Option } from "metabase/components/Select";
import ParameterValueWidget from "metabase/parameters/components/ParameterValueWidget";

import { getParameterOptionsForField } from "metabase/parameters/utils/template-tag-options";
import type { TemplateTag } from "metabase-types/types/Query";
import type { Database } from "metabase-types/types/Database";

import Field from "metabase-lib/lib/metadata/Field";
import { fetchField } from "metabase/redux/metadata";
import { getMetadata } from "metabase/selectors/metadata";
import { SchemaTableAndFieldDataSelector } from "metabase/query_builder/components/DataSelector";
import Metadata from "metabase-lib/lib/metadata/Metadata";
import MetabaseSettings from "metabase/lib/settings";
import type { FieldId } from "metabase-types/types/Field";

type Props = {
  tag: TemplateTag,
  setTemplateTag: (tag: TemplateTag) => void,
  databaseFields: Field[],
  database: Database,
  databases: Database[],
  metadata: Metadata,
  fetchField: FieldId => void,
};

@connect(state => ({ metadata: getMetadata(state) }), { fetchField })
export default class TagEditorParam extends Component {
  props: Props;

  UNSAFE_componentWillMount() {
    const { tag, fetchField } = this.props;

    if (tag.type === "dimension" && Array.isArray(tag.dimension)) {
      const fieldId = tag.dimension[1];
      // Field values might already have been loaded so force the load of other field information too
      fetchField(fieldId, true);
    }
  }

  setType(type) {
    const { tag, setTemplateTag } = this.props;

    if (tag.type !== type) {
      setTemplateTag({
        ...tag,
        type: type,
        dimension: undefined,
        "widget-type": undefined,
      });
    }
  }

  setWidgetType(widgetType) {
    const { tag, setTemplateTag, setParameterValue } = this.props;

    if (tag["widget-type"] !== widgetType) {
      setTemplateTag({ ...this.props.tag, "widget-type": widgetType });
      setParameterValue(tag.id, null);
    }
  }

  setRequired(required) {
    const { tag, setTemplateTag } = this.props;

    if (tag.required !== required) {
      setTemplateTag({ ...tag, required: required, default: undefined });
    }
  }

  setParameterAttribute(attr, val) {
    // only register an update if the value actually changes
    if (this.props.tag[attr] !== val) {
      this.props.setTemplateTag({
        ...this.props.tag,
        [attr]: val,
      });
    }
  }

  setDimension(fieldId) {
    const { tag, setTemplateTag, metadata } = this.props;
    const dimension = ["field", fieldId, null];
    if (!_.isEqual(tag.dimension !== dimension)) {
      const field = metadata.field(dimension[1]);
      if (!field) {
        return;
      }
      const options = getParameterOptionsForField(field);
      let widgetType;
      if (
        tag["widget-type"] &&
        _.findWhere(options, { type: tag["widget-type"] })
      ) {
        widgetType = tag["widget-type"];
      } else if (options.length > 0) {
        widgetType = options[0].type;
      }
      setTemplateTag({
        ...tag,
        dimension,
        "widget-type": widgetType,
      });
    }
  }

  render() {
    const { tag, database, databases, metadata, parameter } = this.props;
    let widgetOptions = [],
      table,
      fieldMetadataLoaded = false;
    if (tag.type === "dimension" && Array.isArray(tag.dimension)) {
      const field = metadata.field(tag.dimension[1]);

      if (field) {
        widgetOptions = getParameterOptionsForField(field);
        table = field.table;
        fieldMetadataLoaded = true;
      }
    }

    const isDimension = tag.type === "dimension";
    const hasSelectedDimensionField =
      isDimension && Array.isArray(tag.dimension);
    const hasWidgetOptions = widgetOptions && widgetOptions.length > 0;

    return (
      <div className="px3 pt3 mb1 border-top">
        <h4 className="text-medium py1">{t`Variable name`}</h4>
        <h3 className="text-heavy text-brand align-self-end mb4">{tag.name}</h3>

        <div className="pb4">
          <h4 className="text-medium pb1">{t`Variable type`}</h4>
          <Select
            className="block"
            value={tag.type}
            onChange={e => this.setType(e.target.value)}
            isInitiallyOpen={!tag.type}
            placeholder={t`Select…`}
            height={300}
          >
            <Option value="text">{t`Text`}</Option>
            <Option value="number">{t`Number`}</Option>
            <Option value="date">{t`Date`}</Option>
            <Option value="dimension">{t`Field Filter`}</Option>
          </Select>
        </div>

        {tag.type === "dimension" && (
          <div className="pb4">
            <h4 className="text-medium pb1">
              {t`Field to map to`}
              {tag.dimension == null && (
                <span className="text-error mx1">(required)</span>
              )}
            </h4>

            {(!hasSelectedDimensionField ||
              (hasSelectedDimensionField && fieldMetadataLoaded)) && (
              <SchemaTableAndFieldDataSelector
                databases={databases}
                selectedDatabaseId={database ? database.id : null}
                selectedTableId={table ? table.id : null}
                selectedFieldId={
                  hasSelectedDimensionField ? tag.dimension[1] : null
                }
                setFieldFn={fieldId => this.setDimension(fieldId)}
                className="AdminSelect flex align-center"
                isInitiallyOpen={!tag.dimension}
                triggerIconSize={12}
                renderAsSelect={true}
              />
            )}
          </div>
        )}

        {hasSelectedDimensionField && (
          <div className="pb4">
            <h4 className="text-medium pb1">{t`Filter widget type`}</h4>
            <Select
              className="block"
              // avoid `undefined` value because it makes the component "uncontrollable"
              // (see Uncontrollable.jsx, metabase#13825)
              value={tag["widget-type"] || "none"}
              onChange={e =>
                this.setWidgetType(
                  e.target.value === "none" ? undefined : e.target.value,
                )
              }
              isInitiallyOpen={!tag["widget-type"] && hasWidgetOptions}
              placeholder={t`Select…`}
            >
              {[{ name: "None", type: "none" }]
                .concat(widgetOptions)
                .map(widgetOption => (
                  <Option key={widgetOption.type} value={widgetOption.type}>
                    {widgetOption.name}
                  </Option>
                ))}
            </Select>
            {!hasWidgetOptions && (
              <p>
                {t`There aren't any filter widgets for this type of field yet.`}{" "}
                <Link
                  to={MetabaseSettings.docsUrl(
                    "users-guide/13-sql-parameters",
                    "the-field-filter-variable-type",
                  )}
                  target="_blank"
                  className="link"
                >
                  {t`Learn more`}
                </Link>
              </p>
            )}
          </div>
        )}

        {(hasWidgetOptions || !isDimension) && (
          <div className="pb4">
            <h4 className="text-medium pb1">{t`Filter widget label`}</h4>
            <InputBlurChange
              type="text"
              value={tag["display-name"]}
              className="AdminSelect p1 text-bold text-dark bordered border-medium rounded full"
              style={{ fontSize: "14px" }}
              onBlurChange={e =>
                this.setParameterAttribute("display-name", e.target.value)
              }
            />
          </div>
        )}

        <div className="pb3">
          <h4 className="text-medium pb1">{t`Required?`}</h4>
          <Toggle
            value={tag.required}
            onChange={value => this.setRequired(value)}
          />
        </div>

        {((tag.type !== "dimension" && tag.required) ||
          tag.type === "dimension" ||
          tag["widget-type"]) && (
          <div className="pb3">
            <h4 className="text-medium pb1">{t`Default filter widget value`}</h4>
            <ParameterValueWidget
              parameter={
                tag.type === "dimension"
                  ? parameter || {
                      fields: [],
                      ...tag,
                      type:
                        tag["widget-type"] ||
                        (tag.type === "date" ? "date/single" : null),
                    }
                  : {
                      fields: [],
                      type:
                        tag["widget-type"] ||
                        (tag.type === "date" ? "date/single" : null),
                    }
              }
              value={tag.default}
              setValue={value => this.setParameterAttribute("default", value)}
              className="AdminSelect p1 text-bold text-medium bordered border-medium rounded bg-white"
              isEditing
              commitImmediately
            />
          </div>
        )}
      </div>
    );
  }
}
