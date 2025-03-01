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
import PropTypes from "prop-types";
import Immutable from "immutable";

import { tabLabel, tabIcon } from "@app/uiTheme/less/layout.less";
import { nav, navBtn, navBtnActive } from "./NavPanel.less";

export default class NavPanel extends PureComponent {
  static propTypes = {
    changeTab: PropTypes.func.isRequired,
    activeTab: PropTypes.string,
    tabs: PropTypes.instanceOf(Immutable.OrderedMap),
    showSingleTab: PropTypes.bool,
  };

  static defaultProps = {
    showSingleTab: false,
  };

  renderTabLabel = ({ text, icon = null }) => {
    return (
      <span className={tabLabel}>
        <span>{text}</span>
        {icon && (
          <span className={tabIcon}>
            <dremio-icon
              name={icon.name}
              alt={icon.alt || ""}
              style={icon.style || ""}
            />
          </span>
        )}
      </span>
    );
  };

  render() {
    const { showSingleTab, tabs } = this.props;

    const invalidTabCount = showSingleTab
      ? tabs.count() < 1
      : tabs.count() <= 1;

    if (invalidTabCount) {
      return null;
    }

    const children = this.props.tabs
      .map((tab, key) => {
        const labelConfig = typeof tab === "string" ? { text: tab } : tab;
        return (
          <div
            data-qa={key}
            key={key}
            onClick={this.props.changeTab.bind(this, key)}
            className={this.props.activeTab === key ? navBtnActive : navBtn}
          >
            {this.renderTabLabel(labelConfig)}
          </div>
        );
      })
      .toArray();

    return (
      <div data-qa="nav-panel" className={nav}>
        {children}
      </div>
    );
  }
}
