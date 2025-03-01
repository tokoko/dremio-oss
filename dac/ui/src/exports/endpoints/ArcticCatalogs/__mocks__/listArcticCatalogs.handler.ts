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

import { rest } from "msw";
import type { ArcticCatalogResponse } from "../ArcticCatalog.type";
import { listArcticCatalogsUrl } from "../listArcticCatalogs";
import { sampleCatalogs } from "./sampleCatalogs";

const originalMockData = sampleCatalogs;
let mockData = originalMockData;

export const setMockData = (newMockData: ArcticCatalogResponse[]) => {
  mockData = newMockData;
};

export const restoreMockData = () => {
  mockData = originalMockData;
};

export const listArcticCatalogsHandler = rest.get(
  listArcticCatalogsUrl.replace(`//${window.location.host}`, ""),
  (_req, res, ctx) => {
    return res(ctx.delay(750), ctx.json(mockData));
  }
);
