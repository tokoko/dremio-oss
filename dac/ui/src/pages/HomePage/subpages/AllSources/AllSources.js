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
import { connect } from "react-redux";
import Immutable from "immutable";
import shallowEqual from "shallowequal";
import PropTypes from "prop-types";
import DocumentTitle from "react-document-title";
import { injectIntl } from "react-intl";

import HomePage from "pages/HomePage/HomePage";
import { loadSourceListData } from "actions/resources/sources";
import { getSources } from "selectors/home";

import {
  isDatabaseType,
  isMetastoreSourceType,
  isObjectStorageSourceType,
  isDataPlaneSourceType,
} from "@app/constants/sourceTypes";
import AllSourcesView from "./AllSourcesView.js";

@injectIntl
export class AllSources extends PureComponent {
  static propTypes = {
    location: PropTypes.object.isRequired,
    sources: PropTypes.instanceOf(Immutable.List),
    loadSourceListData: PropTypes.func,
    intl: PropTypes.object.isRequired,
  };

  componentWillReceiveProps(nextProps) {
    if (!shallowEqual(this.props.location.query, nextProps.location.query)) {
      this.props.loadSourceListData();
    }
  }

  render() {
    const { location, sources, intl } = this.props;
    const isExternalSource = location.pathname === "/sources/external/list";
    const isDataPlaneSource = location.pathname === "/sources/dataplane/list";
    const isObjectStorageSource =
      location.pathname === "/sources/objectStorage/list";
    const isMetastoreSource = location.pathname === "/sources/metastore/list";

    /*eslint no-nested-ternary: "off"*/
    const headerId = isExternalSource
      ? "Source.AllDatabaseSources"
      : isObjectStorageSource
      ? "Source.AllObjectStorage"
      : isMetastoreSource
      ? "Source.AllMetastores"
      : "Source.AllDataPlanes";

    const title = intl.formatMessage({ id: headerId });
    const metastoreSource = sources.filter((source) =>
      isMetastoreSourceType(source.get("type"))
    );

    const objectStorageSource = sources.filter((source) =>
      isObjectStorageSourceType(source.get("type"))
    );

    const databaseSources = sources.filter((source) =>
      isDatabaseType(source.get("type"))
    );
    const dataPlaneSources = sources.filter((source) =>
      isDataPlaneSourceType(source.get("type"))
    );

    /*eslint no-nested-ternary: "off"*/
    const filteredSources = isExternalSource
      ? databaseSources
      : isObjectStorageSource
      ? objectStorageSource
      : isMetastoreSource
      ? metastoreSource
      : dataPlaneSources;

    return (
      <HomePage location={location}>
        <DocumentTitle title={title} />
        <AllSourcesView
          title={title}
          filters={this.filters}
          isExternalSource={isExternalSource}
          isDataPlaneSource={isDataPlaneSource}
          isObjectStorageSource={isObjectStorageSource}
          sources={filteredSources}
        />
      </HomePage>
    );
  }
}

function mapStateToProps(state) {
  return {
    sources: getSources(state),
  };
}

export default connect(mapStateToProps, {
  loadSourceListData,
})(AllSources);
