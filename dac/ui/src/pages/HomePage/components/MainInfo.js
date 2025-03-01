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
import { Component } from "react";
import { connect } from "react-redux";
import { Link } from "react-router";
import Immutable from "immutable";
import PropTypes from "prop-types";
import DocumentTitle from "react-document-title";
import { FormattedMessage, injectIntl } from "react-intl";
import urlParse from "url-parse";

import MainInfoMixin from "dyn-load/pages/HomePage/components/MainInfoMixin";
import LinkWithRef from "@app/components/LinkWithRef/LinkWithRef";
import { loadWiki } from "@app/actions/home";
import DatasetMenu from "components/Menus/HomePage/DatasetMenu";
import FolderMenu from "components/Menus/HomePage/FolderMenu";

import BreadCrumbs, { formatFullPath } from "components/BreadCrumbs";
import { getRootEntityType } from "utils/pathUtils";
import SettingsBtn from "components/Buttons/SettingsBtn";
import { ENTITY_TYPES } from "@app/constants/Constants";
import localStorageUtils from "@app/utils/storageUtils/localStorageUtils";
import { IconButton } from "dremio-ui-lib";
import { TagsAlert } from "@app/pages/HomePage/components/TagsAlert";

import { NESSIE, ARCTIC } from "@app/constants/sourceTypes";
import { tableStyles } from "../tableStyles";
import BrowseTable from "./BrowseTable";
import { HeaderButtons } from "./HeaderButtons";
import MainInfoItemNameAndTag from "./MainInfoItemNameAndTag";
import WikiView from "./WikiView";
import SourceBranchPicker from "./SourceBranchPicker/SourceBranchPicker";
import { getSortedSources } from "@app/selectors/home";
import { getSourceByName } from "@app/utils/nessieUtils";
import ProjectHistoryButton from "@app/exports/pages/ArcticCatalog/components/ProjectHistoryButton";
import { selectState } from "@app/selectors/nessie/nessie";
import { constructArcticUrl } from "@app/exports/pages/ArcticCatalog/arctic-catalog-utils";

const folderPath = "/folder/";

const shortcutBtnTypes = {
  edit: "edit",
  settings: "settings",
};

const getEntityId = (props) => {
  return props && props.entity ? props.entity.get("id") : null;
};

@MainInfoMixin
export class MainInfoView extends Component {
  static contextTypes = {
    location: PropTypes.object.isRequired,
  };

  static propTypes = {
    entity: PropTypes.instanceOf(Immutable.Map),
    entityType: PropTypes.oneOf(Object.values(ENTITY_TYPES)),
    viewState: PropTypes.instanceOf(Immutable.Map),
    updateRightTreeVisibility: PropTypes.func,
    rightTreeVisible: PropTypes.bool,
    isInProgress: PropTypes.bool,
    intl: PropTypes.object.isRequired,
    fetchWiki: PropTypes.func, // (entityId) => Promise
    source: PropTypes.instanceOf(Immutable.Map),
    canUploadFile: PropTypes.bool,
    isSonarSource: PropTypes.bool,
    rootEntityType: PropTypes.string,
    nessieState: PropTypes.object,
  };

  static defaultProps = {
    viewState: Immutable.Map(),
  };

  state = {
    isWikiShown: localStorageUtils.getWikiVisibleState(),
  };

  componentDidMount() {
    this.fetchWiki();
  }

  componentDidUpdate(prevProps) {
    this.fetchWiki(prevProps);
  }

  fetchWiki(prevProps) {
    const oldId = getEntityId(prevProps);
    const newId = getEntityId(this.props);
    if (newId && oldId !== newId && !this.isArctic()) {
      this.props.fetchWiki(newId);
    }
  }

  getActionCell(item) {
    return <ActionWrap>{this.getActionCellButtons(item)}</ActionWrap>;
  }

  getActionCellButtons(item) {
    const dataType = item.get("fileType") || "dataset";
    switch (dataType) {
      case "folder":
        return this.getFolderActionButtons(item);
      case "file":
        return this.getFileActionButtons(item, item.get("permissions"));
      case "dataset":
        return this.getShortcutButtons(item, dataType);
      case "physicalDatasets":
        return this.getShortcutButtons(item, "physicalDataset"); // entities collection uses type name without last 's'
      // looks like 'raw', 'database', and 'table' are legacy entity types that are obsolete.
      default:
        throw new Error("unknown dataType");
    }
  }

  getFolderActionButtons(folder) {
    const isFileSystemFolder = !!folder.get("fileSystemFolder");
    const isQueryAble = folder.get("queryable");
    const permissions = folder.get("permissions")
      ? folder.get("permissions").toJS()
      : null;
    const isAdmin = localStorageUtils.isUserAnAdmin();

    if (isFileSystemFolder && isQueryAble) {
      return this.getShortcutButtons(folder, "folder");
    } else if (
      this.checkToRenderConvertFolderButton(isFileSystemFolder, permissions)
    ) {
      return [
        this.renderConvertButton(folder, {
          icon: <dremio-icon name="interface/format-folder" />,
          tooltip: "Folder.FolderFormat",
          to: {
            ...this.context.location,
            state: {
              modal: "DatasetSettingsModal",
              tab: "format",
              entityType: folder.get("entityType"),
              entityId: folder.get("id"),
              query: { then: "query" },
              isHomePage: true,
            },
          },
        }),
        this.getSettingsBtnByType(<FolderMenu folder={folder} />, folder),
      ];
    } else if (
      isAdmin ||
      (permissions &&
        (permissions.canAlter ||
          permissions.canRead ||
          permissions.canEditAccessControlList ||
          permissions.canDelete))
    ) {
      return this.getSettingsBtnByType(<FolderMenu folder={folder} />, folder);
    } else {
      return;
    }
  }

  getFileActionButtons(file, permissions) {
    const isAdmin = localStorageUtils.isUserAnAdmin();
    const isQueryAble = file.get("queryable");
    if (isQueryAble) {
      return this.getShortcutButtons(file, "file");
    }
    // DX-12874 not queryable files should have only promote button
    if (this.checkToRenderConvertFileButton(isAdmin, permissions)) {
      return [
        this.renderConvertButton(file, {
          icon: <dremio-icon name="interface/format-file" />,
          tooltip: "File.FileFormat",
          to: {
            ...this.context.location,
            state: {
              modal: "DatasetSettingsModal",
              tab: "format",
              entityType: file.get("entityType"),
              entityId: file.get("id"),
              queryable: file.get("queryable"),
              fullPath: file.get("filePath"),
              query: { then: "query" },
              isHomePage: true,
            },
          },
        }),
      ];
    } else {
      return;
    }
  }

  // this method is targeted for dataset like entities: PDS, VDS and queriable files
  getShortcutButtons(item, entityType) {
    const allBtns = this.getShortcutButtonsData(
      item,
      entityType,
      shortcutBtnTypes
    );
    return [
      ...allBtns
        // select buttons to be shown
        .filter((btn) => btn.isShown)
        // return rendered link buttons
        .map((btnType, index) => (
          <IconButton
            as={LinkWithRef}
            to={btnType.link}
            tooltip={btnType.tooltip}
            key={item.get("id") + index}
            className="main-settings-btn min-btn"
            data-qa={btnType.type}
          >
            {btnType.label}
          </IconButton>
        )),
      this.getSettingsBtnByType(
        <DatasetMenu entity={item} entityType={entityType} />,
        item
      ),
    ];
  }

  getInlineIcon(icon) {
    return <dremio-icon name={icon} data-qa={icon} />;
  }

  getWikiButtonLink(item) {
    const url = item.getIn(["links", "query"]);
    const parseUrl = urlParse(url);
    return `${parseUrl.pathname}/wiki${parseUrl.query}`;
  }

  getSettingsBtnByType(menu, item) {
    return (
      <SettingsBtn
        handleSettingsClose={this.handleSettingsClose.bind(this)}
        handleSettingsOpen={this.handleSettingsOpen.bind(this)}
        dataQa={item.get("name")}
        menu={menu}
        classStr="main-settings-btn min-btn catalog-btn"
        key={`${item.get("name")}-${item.get("id")}`}
        tooltip="Common.More"
        hideArrowIcon
      >
        {this.getInlineIcon("interface/more")}
      </SettingsBtn>
    );
  }

  getRow(item) {
    const [name, jobs, action] = this.getTableColumns();
    const jobsCount =
      item.get("jobCount") || item.getIn(["extendedConfig", "jobCount"]) || 0;
    const isArcticSource = this.isArctic();
    return {
      rowClassName: item.get("name"),
      data: {
        [name.key]: {
          node: () => (
            <MainInfoItemNameAndTag item={item} isIceberg={isArcticSource} />
          ),
          value: item.get("name"),
        },
        [jobs.key]: {
          node: () => (
            <Link to={item.getIn(["links", "jobs"])}>{jobsCount}</Link>
          ),
          value: jobsCount,
        },
        [action.key]: {
          node: () => this.getActionCell(item),
        },
      },
    };
  }

  getTableColumns() {
    const { intl } = this.props;

    return [
      {
        key: "name",
        label: intl.formatMessage({ id: "Common.Name" }),
        infoContent: <TagsAlert />,
        flexGrow: 1,
      },
      {
        key: "jobs",
        label: intl.formatMessage({ id: "Job.Jobs" }),
        style: tableStyles.digitColumn,
        columnAlignment: "alignRight",
        headerStyle: { justifyContent: "flex-end" },
        isFixedWidth: true,
        width: 66,
      },
      {
        key: "action",
        label: " ",
        style: tableStyles.actionColumn,
        isFixedWidth: true,
        width: 140,
        className: "row-buttons",
        headerClassName: "row-buttons",
        disableSort: true,
      },
    ];
  }

  getTableData() {
    const contents = this.props.entity && this.props.entity.get("contents");
    let rows = Immutable.List();
    if (contents && !contents.isEmpty()) {
      const appendRow = (dataset) => {
        // DX-10700 there could be the cases, when we reset a entity cache (delete all entities of the certain type), but there are references on these intities in the state;
        // For example, we clearing folders, when we navigating to another folder, but sources could have a reference by id on these folder. As folder would not be found, here we would have undefined
        //skip such cases
        if (!dataset) return;
        rows = rows.push(this.getRow(dataset));
      };
      contents.get("datasets").forEach(appendRow);
      contents.get("folders").forEach(appendRow);
      contents.get("files").forEach(appendRow);
      contents.get("physicalDatasets").forEach(appendRow);
    }
    return rows;
  }

  handleSettingsClose(settingsWrap) {
    $(settingsWrap).parents("tr").removeClass("hovered");
  }

  handleSettingsOpen(settingsWrap) {
    $(settingsWrap).parents("tr").addClass("hovered");
  }

  isReadonly(spaceList) {
    const { pathname } = this.context.location;
    if (spaceList !== undefined) {
      return !!spaceList.find((item) => {
        if (item.href === pathname) {
          return item.readonly;
        }
      });
    }
    return false;
  }

  toggleWikiShow = () => {
    let newValue;
    this.setState(
      (prevState) => ({
        isWikiShown: (newValue = !prevState.isWikiShown),
      }),
      () => {
        localStorageUtils.setWikiVisibleState(newValue);
      }
    );
  };

  toggleRightTree = () => {
    this.props.updateRightTreeVisibility(!this.props.rightTreeVisible);
  };

  isNessie = () => {
    const { source, entity } = this.props;
    return entity && source && source.get("type") === NESSIE;
  };

  isArctic = () => {
    const { source, entity } = this.props;
    return entity && source && source.get("type") === ARCTIC;
  };

  isNeitherNessieOrArctic = () => {
    return !this.isNessie() && !this.isArctic();
  };

  constructArcticSourceLink = () => {
    const { source, nessieState = {} } = this.props;
    const { hash, reference } = nessieState;
    const { pathname } = this.context.location;
    let namespace = "";
    if (pathname.includes(folderPath)) {
      namespace = pathname.substring(
        pathname.indexOf(folderPath) + folderPath.length,
        pathname.length
      );
      if (reference) {
        namespace = `${reference?.name}/${namespace}`;
      }
    }
    return constructArcticUrl({
      type: "source",
      baseUrl: `/sources/arctic/${source.get("name")}`,
      tab: "commits",
      namespace: namespace,
      hash: hash ? `?hash=${hash}` : "",
    });
  };

  renderExternalLink = () => {
    const { source } = this.props;
    if (this.isNeitherNessieOrArctic()) return null;
    else if (this.isNessie()) {
      return (
        <Link to={`/sources/dataplane/${source.get("name")}/branches`}>
          <FormattedMessage id="Nessie.ViewAllBranches" />
        </Link>
      );
    } else {
      return (
        <Link
          to={this.constructArcticSourceLink()}
          style={{ textDecoration: "none" }}
        >
          <ProjectHistoryButton />
        </Link>
      );
    }
  };

  renderTitleExtraContent = () => {
    const { source } = this.props;
    if (this.isNeitherNessieOrArctic()) return null;
    return <SourceBranchPicker source={source.toJS()} />;
  };

  render() {
    const { canUploadFile, entity, viewState, isSonarSource, rootEntityType } =
      this.props;
    const { pathname } = this.context.location;
    const showWiki =
      entity && !entity.get("fileSystemFolder") && !this.isArctic(); // should be removed when DX-13804 would be fixed

    const buttons = entity && (
      <HeaderButtons
        entity={entity}
        rootEntityType={rootEntityType}
        rightTreeVisible={this.props.rightTreeVisible}
        toggleVisibility={this.toggleRightTree}
        canUploadFile={canUploadFile}
        isSonarSource={isSonarSource}
      />
    );

    return (
      <BrowseTable
        title={
          entity && (
            <BreadCrumbs
              fullPath={entity.get("fullPathList")}
              pathname={pathname}
              showCopyButton
              includeQuotes
            />
          )
        }
        buttons={buttons}
        key={pathname} /* trick to clear out the searchbox on navigation */
        columns={this.getTableColumns()}
        rightSidebar={showWiki ? <WikiView item={entity} /> : null}
        rightSidebarExpanded={this.state.isWikiShown}
        toggleSidebar={this.toggleWikiShow}
        tableData={this.getTableData()}
        viewState={viewState}
        renderExternalLink={this.renderExternalLink}
        renderTitleExtraContent={this.renderTitleExtraContent}
        disableZebraStripes
        rowHeight={40}
      >
        <DocumentTitle
          title={
            (entity && formatFullPath(entity.get("fullPathList")).join(".")) ||
            ""
          }
        />
      </BrowseTable>
    );
  }
}
MainInfoView = injectIntl(MainInfoView);

function ActionWrap({ children }) {
  return <span className="action-wrap">{children}</span>;
}
ActionWrap.propTypes = {
  children: PropTypes.node,
};

function mapStateToProps(state, props) {
  const rootEntityType = getRootEntityType(
    props.entity?.getIn(["links", "self"])
  );

  let isSonarSource = false;
  const sources = getSortedSources(state);
  if (
    sources &&
    rootEntityType === ENTITY_TYPES.source &&
    props.entity?.get("entityType") === ENTITY_TYPES.folder
  ) {
    const entityJS = props.entity.toJS();
    const parentSource = getSourceByName(
      entityJS.fullPathList[0],
      sources.toJS()
    );

    isSonarSource = [NESSIE, ARCTIC].includes(parentSource?.type);
  }

  const nessieState = selectState(state.nessie, props.source?.get("name"));

  return {
    rootEntityType,
    isSonarSource,
    canUploadFile: state.privileges?.project?.canUploadFile,
    nessieState,
  };
}

export default connect(mapStateToProps, (dispatch) => ({
  fetchWiki: loadWiki(dispatch),
}))(MainInfoView);
