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
import { Type } from "@app/services/nessie/client";
//@ts-ignore
import { useProjectContext } from "@inject/utils/storageUtils/localStorageUtils";

export function getIconByType(type?: string | null, elements?: string[]) {
  switch (type) {
    case Type.IcebergTable:
    case Type.DeltaLakeTable:
      return { type: "PhysicalDataset", id: `Nessie.${type}` };
    case "ICEBERG_VIEW": // TODO, need to update generated types
      return { type: "VirtualDataset", id: `Nessie.${type}` };
    default:
      return {
        type: elements && elements.length > 1 ? "Folder" : "Space",
        id: "Nessie.Namespace",
      };
  }
}

export function getUrlByType(type: string | null, fullPath: string) {
  switch (type) {
    case Type.IcebergTable:
    case Type.DeltaLakeTable:
      return `/table/${fullPath}`;
    case "ICEBERG_VIEW": // TODO, need to update generated types
      return `/view/${fullPath}`;
    default:
      return `/namespace/${fullPath}`;
  }
}

export function useProjectInfo(): { id: string; name: string } {
  const ctx = useProjectContext();
  return {
    id: ctx?.id,
    name: ctx?.name,
  };
}
