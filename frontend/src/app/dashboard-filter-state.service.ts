import { Injectable } from '@angular/core';

export type DashboardReportPeriod = 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'CUSTOM';

export interface DashboardFilters {
  batchId: string;
  country: string;
  reportGroupId: string;
  reportPeriod: DashboardReportPeriod;
  startDate: string;
  endDate: string;
}

/**
 * Remembers the dashboard's last-applied filters for the lifetime of the app session, independent
 * of the URL. HomeComponent is destroyed and recreated every time the user navigates away from
 * `/batches` and back — and most of the app's own links back to the dashboard (the logo, the
 * "Batch View" nav link, "Back to dashboard" buttons on other pages) are plain routerLinks with no
 * query params, so the URL alone can't carry the previous filters forward. This service is the
 * actual source of truth for "what the user last set the dashboard to"; the URL round-trip
 * (persistRouteFilters/restoreRouteFilters in HomeComponent) remains layered on top only so a
 * bookmarked or shared dashboard link still works.
 */
@Injectable({ providedIn: 'root' })
export class DashboardFilterStateService {
  private current: DashboardFilters | null = null;

  get(): DashboardFilters | null {
    return this.current;
  }

  set(filters: DashboardFilters): void {
    this.current = filters;
  }
}
