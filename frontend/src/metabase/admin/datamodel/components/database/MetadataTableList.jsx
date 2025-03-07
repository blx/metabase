/* eslint-disable react/prop-types */
import React, { Component } from "react";
import PropTypes from "prop-types";
import { connect } from "react-redux";

import Tables from "metabase/entities/tables";

import Icon from "metabase/components/Icon";

import { t, ngettext, msgid } from "ttag";

import _ from "underscore";
import cx from "classnames";

import { regexpEscape } from "metabase/lib/string";
import { color } from "metabase/lib/colors";

@connect(null, {
  setVisibilityForTables: (tables, visibility_type) =>
    Tables.actions.bulkUpdate({
      ids: tables.map(t => t.id),
      visibility_type,
    }),
})
export default class MetadataTableList extends Component {
  constructor(props, context) {
    super(props, context);

    this.state = {
      searchText: "",
      searchRegex: null,
    };

    _.bindAll(this, "updateSearchText");
  }

  static propTypes = {
    tableId: PropTypes.number,
    tables: PropTypes.array.isRequired,
    selectTable: PropTypes.func.isRequired,
  };

  updateSearchText(event) {
    this.setState({
      searchText: event.target.value,
      searchRegex: event.target.value
        ? new RegExp(regexpEscape(event.target.value), "i")
        : null,
    });
  }

  partitionedTables() {
    const regex = this.state.searchRegex;
    const [hiddenTables, queryableTables] = _.chain(this.props.tables)
      .filter(
        table =>
          !regex || regex.test(table.display_name) || regex.test(table.name),
      )
      .sortBy("display_name")
      .partition(table => table.visibility_type != null)
      .value();
    return { hiddenTables, queryableTables };
  }

  render() {
    let queryableTablesHeader, hiddenTablesHeader;
    const { hiddenTables, queryableTables } = this.partitionedTables();

    const { setVisibilityForTables } = this.props;

    if (queryableTables.length > 0) {
      queryableTablesHeader = (
        <li className="AdminList-section">
          {(n =>
            ngettext(msgid`${n} Queryable Table`, `${n} Queryable Tables`, n))(
            queryableTables.length,
          )}
          <ToggleHiddenButton
            setVisibilityForTables={setVisibilityForTables}
            tables={queryableTables}
            isHidden={false}
          />
        </li>
      );
    }
    if (hiddenTables.length > 0) {
      hiddenTablesHeader = (
        <li className="AdminList-section">
          {(n => ngettext(msgid`${n} Hidden Table`, `${n} Hidden Tables`, n))(
            hiddenTables.length,
          )}
          <ToggleHiddenButton
            setVisibilityForTables={setVisibilityForTables}
            tables={hiddenTables}
            isHidden={true}
          />
        </li>
      );
    }
    if (queryableTables.length === 0 && hiddenTables.length === 0) {
      queryableTablesHeader = <li className="AdminList-section">0 Tables</li>;
    }

    const { tableId, selectTable } = this.props;

    return (
      <div className="MetadataEditor-table-list AdminList flex-no-shrink">
        <div className="AdminList-search">
          <Icon name="search" size={16} />
          <input
            className="AdminInput pl4 border-bottom"
            type="text"
            placeholder={t`Find a table`}
            value={this.state.searchText}
            onChange={this.updateSearchText}
          />
        </div>
        {(this.props.onBack || this.props.schema) && (
          <h4 className="p2 border-bottom">
            {this.props.onBack && (
              <span
                className="text-brand cursor-pointer"
                onClick={this.props.onBack}
              >
                <Icon name="chevronleft" size={10} />
                {t`Schemas`}
              </span>
            )}
            {this.props.onBack && this.props.schema && (
              <span className="mx1">-</span>
            )}
            {this.props.schema && <span> {this.props.schema}</span>}
          </h4>
        )}

        <ul className="AdminList-items">
          {queryableTablesHeader}
          {queryableTables.map(table => (
            <TableRow
              key={table.id}
              table={table}
              selected={tableId === table.id}
              selectTable={selectTable}
              setVisibilityForTables={setVisibilityForTables}
            />
          ))}
          {hiddenTablesHeader}
          {hiddenTables.map(table => (
            <TableRow
              key={table.id}
              table={table}
              selected={tableId === table.id}
              selectTable={selectTable}
              setVisibilityForTables={setVisibilityForTables}
            />
          ))}
        </ul>
      </div>
    );
  }
}

function TableRow({
  table,
  selectTable,
  toggleHidden,
  selected,
  setVisibilityForTables,
}) {
  return (
    <li key={table.id} className="hover-parent hover--visibility">
      <a
        className={cx(
          "AdminList-item flex align-center no-decoration text-wrap justify-between",
          { selected, disabled: !table.initial_sync },
        )}
        onClick={() => selectTable(table)}
      >
        {table.display_name}
        {table.initial_sync && (
          <div className="hover-child float-right">
            <ToggleHiddenButton
              tables={[table]}
              isHidden={table.visibility_type != null}
              setVisibilityForTables={setVisibilityForTables}
            />
          </div>
        )}
      </a>
    </li>
  );
}

function ToggleHiddenButton({ setVisibilityForTables, tables, isHidden }) {
  const [isLoading, setIsLoading] = React.useState(false);

  const handleToggle = async e => {
    e.stopPropagation();

    if (isLoading) {
      return;
    }

    setIsLoading(true);
    try {
      await setVisibilityForTables(tables, isHidden ? null : "hidden");
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <Icon
      tabIndex="0"
      name={isHidden ? "eye" : "eye_crossed_out"}
      onClick={handleToggle}
      onKeyUp={e => e.key === "Enter" && handleToggle(e)}
      disabled={isLoading}
      tooltip={
        tables.length > 1
          ? isHidden
            ? t`Unhide all`
            : t`Hide all`
          : isHidden
          ? t`Unhide`
          : t`Hide`
      }
      size={18}
      className={cx(
        "float-right",
        isLoading ? "cursor-not-allowed" : "cursor-pointer",
      )}
      hover={{ color: isLoading ? undefined : color("brand") }}
    />
  );
}
