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
import { Component, createRef } from "react";
import { withRouter } from "react-router";
import LinkWithRef from "@app/components/LinkWithRef/LinkWithRef";
import PropTypes from "prop-types";
import Immutable from "immutable";
import classNames from "classnames";
import { injectIntl } from "react-intl";

import { Tooltip } from "@app/components/Tooltip";
import { HISTORY_ITEM_COLOR } from "uiTheme/radium/colors";
import { TIME_DOT_DIAMETER } from "uiTheme/radium/sizes";
import { Button, IconButton } from "dremio-ui-lib";
import EllipsedText from "components/EllipsedText";

import "./TimeDot.less";

export class TimeDot extends Component {
  static propTypes = {
    historyItem: PropTypes.instanceOf(Immutable.Map).isRequired,
    tipVersion: PropTypes.string.isRequired,
    activeVersion: PropTypes.string,
    router: PropTypes.object,
    hideDelay: PropTypes.number,
    location: PropTypes.object.isRequired,
    intl: PropTypes.object.isRequired,
    removeHistoryHover: PropTypes.func,
  };

  static defaultProps = {
    hideDelay: 50,
    historyItem: Immutable.Map(),
  };

  state = {
    open: false,
  };

  targetRef = createRef();

  getTooltipTarget = () => (this.state.open ? this.targetRef.current : null);

  getLinkLocation() {
    const { tipVersion, historyItem, location } = this.props;
    const query = (location && location.query) || {};
    let activeLink = null;

    if (query.version === historyItem.get("datasetVersion")) {
      activeLink = true;
    }
    return {
      pathname: location.pathname,
      query: {
        ...(query.mode ? { mode: query.mode } : {}),
        tipVersion,
        version: historyItem.get("datasetVersion"),
      },
      activeLink,
    };
  }

  handleMouseLeave = () => {
    this.hideTimeout = setTimeout(() => {
      this.setState({
        open: false,
      });
    }, this.props.hideDelay);
  };

  componentWillUnmount() {
    clearTimeout(this.hideTimeout);
  }

  handleMouseEnter = () => {
    const { removeHistoryHover } = this.props;
    clearTimeout(this.hideTimeout);
    this.setState({
      open: true,
    });
    removeHistoryHover && this.props.removeHistoryHover();
  };

  loadHistory = () => {
    const { router } = this.props;

    this.handleMouseLeave();

    router.push(this.getLinkLocation());
  };

  getHistoryPath = () => {
    const { location, tipVersion, historyItem } = this.props;

    const mode = location.query && location.query.mode;
    const basePath = `${location.pathname}?${mode ? "mode=edit&" : ""}`;
    const historyPath = `${basePath}tipVersion=${tipVersion}&version=${historyItem.get(
      "datasetVersion"
    )}`;

    return historyPath;
  };

  renderCompletedContent = (wrap = true) => {
    const {
      historyItem,
      intl: { formatMessage },
    } = this.props;

    const node = (
      <div className="timeDot-content">
        <EllipsedText
          style={{ ...styles.textDesc }}
          text={historyItem.get("transformDescription")}
        />

        <div className="timeDot__buttons">
          <Button
            color="primary"
            title={formatMessage({ id: "Explore.History.Load" })}
            onClick={() => this.loadHistory()}
            disableMargin
          >
            {formatMessage({ id: "Explore.History.Load" })}
          </Button>
          <IconButton
            tooltip={"Explore.History.LoadInNewTab"}
            as={LinkWithRef}
            to={this.getHistoryPath()}
            target="_blank"
            onClick={() => this.handleMouseLeave()}
            className="btn btn__primary --outlined"
          >
            {formatMessage({ id: "Explore.History.LoadInNewTab" })}
          </IconButton>
        </div>
      </div>
    );
    return wrap ? <div>{node}</div> : node;
  };

  renderContent() {
    return (
      <div
        key="dot"
        data-qa="time-dot-target"
        className="timeDot-container"
      ></div>
    );
  }

  render() {
    const linkLocation = this.getLinkLocation();
    const commonProps = {
      onMouseEnter: this.handleMouseEnter,
      onMouseLeave: this.handleMouseLeave,
      ref: this.targetRef,
    };

    return (
      <div
        data-testid="timeDotWrapper"
        className="timeDotWrapper"
        {...commonProps}
      >
        <div
          className={classNames("timeDot", {
            "--active": linkLocation.activeLink,
          })}
        >
          {this.renderContent()}
        </div>
        <Tooltip
          container={document.body}
          placement="right"
          target={this.getTooltipTarget}
          tooltipInnerStyle={styles.popover}
          dataQa="time-dot-popover"
          type="info"
          tooltipInnerClass="textWithHelp__tooltip --white"
          tooltipArrowClass="--white"
        >
          {this.renderCompletedContent()}
        </Tooltip>
      </div>
    );
  }
}

export default withRouter(injectIntl(TimeDot));

const styles = {
  textDesc: {
    width: 255,
    textDecoration: "none",
    flexGrow: 1,
    paddingRight: 10,
    whiteSpace: "inherit",
    marginBottom: 15,
  },
  pointer: {
    cursor: "pointer",
  },
  circle: {
    backgroundColor: HISTORY_ITEM_COLOR,
    width: TIME_DOT_DIAMETER,
    height: TIME_DOT_DIAMETER,
    borderRadius: TIME_DOT_DIAMETER / 2,
  },
  popover: {
    minHeight: 46,
    maxHeight: 298, // to cut last visible line in half in case of overflow
    overflow: "hidden",
    width: 280,
    padding: "12px 15px",
  },
  popoverButtons: {
    display: "flex",
    gap: "8px",
    marginTop: "33px",
  },
};
