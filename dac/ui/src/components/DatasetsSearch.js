/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { PureComponent } from "react";
import Immutable from "immutable";
import PropTypes from "prop-types";
import { Link } from "react-router";
import ViewStateWrapper from "components/ViewStateWrapper";
import { injectIntl } from "react-intl";
import { getIconDataTypeFromDatasetType } from "utils/iconUtils";
import { Tooltip, TagList } from "dremio-ui-lib";

import { bodySmall } from "uiTheme/radium/typography";

import { PALE_NAVY } from "uiTheme/radium/colors";
import DatasetItemLabel from "./Dataset/DatasetItemLabel";
import "./DatasetsSearch.less";

const emptyList = new Immutable.List();

class DatasetsSearch extends PureComponent {
  static propTypes = {
    searchData: PropTypes.instanceOf(Immutable.List).isRequired,
    globalSearch: PropTypes.bool,
    handleSearchHide: PropTypes.func.isRequired,
    inputValue: PropTypes.string,
    searchViewState: PropTypes.instanceOf(Immutable.Map).isRequired,
    intl: PropTypes.object.isRequired,
  };

  onClickDataSetItem = () => {
    this.props.handleSearchHide();
  };

  getDatasetsList(searchData, inputValue) {
    const { globalSearch } = this.props;
    return searchData.map((value, key) => {
      const name = value.getIn(["fullPath", -1]);
      const datasetItem = (
        <div
          key={key}
          style={{ ...styles.datasetItem, ...bodySmall }}
          data-qa={`ds-search-row-${name}`}
          className="search-result-row"
        >
          <div style={styles.datasetData}>
            <DatasetItemLabel
              isSearchItem
              name={name}
              showFullPath
              inputValue={inputValue}
              fullPath={value.get("displayFullPath")}
              typeIcon={getIconDataTypeFromDatasetType(
                value.get("datasetType")
              )}
              placement="right"
            />
          </div>
          {/* DX-11249 <div style={styles.parentDatasetsHolder} data-qa='ds-parent'>
            {this.getParentItems(value, inputValue)}
          </div> */}
          <TagList tags={value.get("tags", emptyList)} className="tagSearch" />
          {this.getActionButtons(value)}
        </div>
      );
      return globalSearch ? (
        <Link
          key={key}
          className="dataset"
          style={{ textDecoration: "none" }}
          to={value.getIn(["links", "self"])}
          onClick={this.onClickDataSetItem}
        >
          {datasetItem}
        </Link>
      ) : (
        datasetItem
      );
    });
  }

  getActionButtons(dataset) {
    const {
      intl: { formatMessage },
    } = this.props;
    return (
      <span className="main-settings-btn min-btn" style={styles.actionButtons}>
        {dataset.getIn(["links", "edit"]) && (
          <Link to={dataset.getIn(["links", "edit"])}>
            <Tooltip title={formatMessage({ id: "Common.Edit" })}>
              <button className="settings-button" data-qa="edit">
                <dremio-icon
                  name="common/Edit"
                  style={styles.icon}
                  alt={formatMessage({ id: "Common.Edit" })}
                ></dremio-icon>
              </button>
            </Tooltip>
          </Link>
        )}
        <Link to={dataset.getIn(["links", "self"])}>
          <Tooltip title={formatMessage({ id: "Common.DoQuery" })}>
            <button className="settings-button" data-qa="query">
              <dremio-icon
                name="common/SQLRunner"
                style={styles.icon}
                alt={formatMessage({ id: "Common.DoQuery" })}
              ></dremio-icon>
            </button>
          </Tooltip>
        </Link>
      </span>
    );
  }

  render() {
    const { searchData, inputValue, searchViewState } = this.props;
    const searchBlock =
      searchData && searchData.size && searchData.size > 0 ? (
        <div>{this.getDatasetsList(searchData, inputValue)}</div>
      ) : (
        <div style={styles.notFound}>{la("No views or tables found")}</div>
      );
    return (
      <section className="datasets-search" style={styles.main}>
        <div className="dataset-wrapper" style={styles.datasetWrapper}>
          <ViewStateWrapper viewState={searchViewState}>
            {searchBlock}
          </ViewStateWrapper>
        </div>
      </section>
    );
  }
}

const styles = {
  main: {
    background: "#fff",
    zIndex: 999,
    color: "#000",
    boxShadow: "-1px 1px 1px #ccc",
  },
  datasetItem: {
    padding: "10px",
    borderBottom: "1px solid rgba(0,0,0,.1)",
    height: 45,
    width: "100%",
    display: "flex",
    alignItems: "center",
  },
  datasetData: {
    margin: "0 0 0 5px",
    minWidth: 300,
  },
  header: {
    height: 38,
    width: "100%",
    background: PALE_NAVY,
    display: "flex",
    alignItems: "center",
    padding: "0 10px",
  },
  datasetWrapper: {
    maxHeight: 360,
    overflow: "auto",
  },
  icon: {
    width: 24,
    height: 24,
  },
  closeIcon: {
    margin: "0 0 0 auto",
    height: 24,
    cursor: "pointer",
  },
  actionButtons: {
    margin: "0 0 0 auto",
  },
  parentDatasetsHolder: {
    display: "flex",
  },
  parentDatasets: {
    display: "flex",
    alignItems: "center",
    margin: "0 10px 0 0",
  },
  notFound: {
    fontSize: 14,
    padding: "16px",
  },
};
export default injectIntl(DatasetsSearch);
