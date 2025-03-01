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
import Menu from "components/Menus/Menu";
import MenuItem from "components/Menus/MenuItem";
import { withRouter } from "react-router";
import * as classes from "./CatalogsMenu.module.less";
import { menuListStyle } from "@app/components/SideNav/SideNavConstants";
import { useIntl } from "react-intl";
import * as PATHS from "../../exports/paths";
import { FeatureSwitch } from "@app/exports/components/FeatureSwitch/FeatureSwitch";
import { ARCTIC_CATALOG } from "@app/exports/flags/ARCTIC_CATALOG";

type CatalogsMenuProps = {
  router: any;
};

const CatalogsMenu = ({ router }: CatalogsMenuProps) => {
  const intl = useIntl();
  const arcticLabel = intl.formatMessage({ id: "SideNav.ArcticCatalogs" });
  const sonarLabel = intl.formatMessage({ id: "SideNav.SonarProjects" });
  return (
    <Menu style={menuListStyle}>
      <MenuItem
        classname={classes["catalog-menu-item"]}
        onClick={() => router.push(PATHS.sonarProjects())}
      >
        <dremio-icon
          name="corporate/sonar"
          alt={sonarLabel}
          class={classes["catalog-menu-item__icon"]}
        />
        {sonarLabel}
      </MenuItem>
      <FeatureSwitch
        flag={ARCTIC_CATALOG}
        renderEnabled={() => (
          <MenuItem
            classname={classes["catalog-menu-item"]}
            onClick={() => router.push(PATHS.arcticCatalogs())}
          >
            <dremio-icon
              name="corporate/arctic"
              alt={arcticLabel}
              class={classes["catalog-menu-item__icon"]}
            />
            {arcticLabel}
          </MenuItem>
        )}
      />
    </Menu>
  );
};

export default withRouter(CatalogsMenu);
