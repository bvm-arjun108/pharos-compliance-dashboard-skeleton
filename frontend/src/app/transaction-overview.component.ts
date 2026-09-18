import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { AfterViewInit, Component, ElementRef, OnDestroy, OnInit, ViewChild, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { DashboardFilterStateService, DashboardReportPeriod } from './dashboard-filter-state.service';

interface CountryOption {
  code: string;
  name: string;
}

interface BatchFilterOptionsResponse {
  countries: CountryOption[];
}

interface ReportGroupOption {
  reportGroupId: number;
  reportGroupName: string | null;
  countryCode: string;
}

interface ReportConfigListItem {
  reportGroupId: number;
  reportGroupName: string | null;
  countryCode: string;
}

interface ReportConfigExplorerResponse {
  configurations: ReportConfigListItem[];
}

// Only the field this page actually reads -- the transaction report page's own search box only
// understands identifier/mtcn, so every result's resolved mtcn is what gets carried into that
// redirect, regardless of whether the search matched on MTCN or external transaction key.
interface TransactionSearchResult {
  mtcn: string | null;
}

type TransactionSearchField = 'MTCN' | 'EXTERNAL_TXN_ID';

interface TransactionSearchResponse {
  query: string;
  results: TransactionSearchResult[];
}

interface TransactionOverview {
  selected: number;
  expected: number;
  excluded: number;
  notReported: number;
}

interface ExclusionReason {
  reason: string;
  count: number;
}

interface NotReportedReason {
  reason: string;
  count: number;
}

type TrendGranularity = 'DAILY' | 'WEEKLY' | 'MONTHLY';

interface BatchHealthTrend {
  periodStart: string;
  periodEnd: string;
  totalReportedTransactions: number;
  totalExcludedTransactions: number;
}

// Transactions Overview's own endpoint (/dashboardDetails/transaction-view) -- Batch View reads
// a separate endpoint with its own response shape, since the two pages don't share a payload
// anymore.
interface TransactionDashboardResponse {
  transactionOverview: TransactionOverview;
  topExclusionReasons: ExclusionReason[];
  notReportedReasons: NotReportedReason[];
  trendGranularity: TrendGranularity;
  batchHealthTrend: BatchHealthTrend[];
  fromDate: string;
  toDate: string;
}

type ReportPeriod = DashboardReportPeriod;

@Component({
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule],
  template: `
    <section class="filter-panel" aria-labelledby="filter-heading">
      <div class="filter-panel__heading">
        <div>
          <p class="eyebrow">Search & filter</p>
          <h2 id="filter-heading">Transaction view criteria</h2>
        </div>
        <div class="filter-panel__actions">
          <button class="text-button" type="button" (click)="resetFilters()">Reset all</button>
        </div>
      </div>

      <form class="filter-form" (submit)="applyFilters($event)">
        <!-- Unscoped lookup, in the same row as the filters below (same slot Batch View's Batch ID
             field takes there) -- resolves an identifier/MTCN/external transaction key via
             TransactionSearchRepository (no date/country/report-group needed) and jumps straight
             to the detailed transaction report on Enter, rather than applying this row's own
             filters. Its own (keydown.enter) handler prevents that keypress from also submitting
             this form as an Apply-filters action. -->
        <label class="field field--search">
          <span>Find a transaction</span>
          <div class="input-shell input-shell--search">
            <select class="search-field-select" [value]="searchField()" (change)="setSearchField($event)" aria-label="Search by">
              <option value="MTCN">MTCN</option>
              <option value="EXTERNAL_TXN_ID">External Txn ID</option>
            </select>
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21 21-4.35-4.35m2.35-5.15a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" /></svg>
            <input
              type="search"
              [placeholder]="searchPlaceholder()"
              autocomplete="off"
              [value]="searchQuery()"
              (input)="setSearchQuery($event)"
              (keydown.enter)="runSearch($event)"
            />
          </div>
        </label>

        <label class="field">
          <span>Country</span>
          @if (countryOptions().length > 0) {
            <select [ngModel]="country()" (ngModelChange)="setCountryValue($event)" [ngModelOptions]="{standalone: true}">
              <!-- No real "all countries" choice here (unlike Batch View's) -- ever_reported/
                   ever_excluded is only meaningful scoped to one country's rules (see the
                   cross-country and rule-side grain discussion), so this is a required field with
                   a disabled placeholder rather than a selectable wildcard. Still uses the same
                   'ALL' sentinel value as Batch View internally so DashboardFilterStateService's
                   shared country field keeps meaning "no real selection" the same way there. -->
              <option value="ALL" disabled>Select a country…</option>
              @for (option of countryOptions(); track option.code) {
                <option [value]="option.code">{{ option.name }}</option>
              }
            </select>
          } @else {
            <select disabled aria-label="Country options loading"><option>Loading countries…</option></select>
          }
        </label>

        <label class="field">
          <span>Report Group</span>
          @if (reportGroupOptions().length > 0) {
            <select [ngModel]="reportGroupId()" (ngModelChange)="setReportGroupValue($event)" [ngModelOptions]="{standalone: true}">
              <option value="ALL">All report groups</option>
              @for (option of filteredReportGroupOptions(); track option.reportGroupId) {
                <option [value]="option.reportGroupId">{{ option.reportGroupName || 'Report group ' + option.reportGroupId }}</option>
              }
            </select>
          } @else {
            <select disabled aria-label="Report group options loading"><option>Loading report groups…</option></select>
          }
        </label>

        <label class="field">
          <span>Report period</span>
          <select [value]="reportPeriod()" (change)="setReportPeriod($event)">
            <option value="TODAY">Today</option>
            <option value="LAST_7_DAYS">Last 7 Days</option>
            <option value="LAST_30_DAYS">Last 30 Days</option>
            <option value="CUSTOM">Custom Date</option>
          </select>
        </label>

        @if (reportPeriod() === 'CUSTOM') {
          <label class="field custom-date">
            <span>From</span>
            <input type="date" [value]="startDate()" (input)="setStartDate($event)" />
          </label>
          <label class="field custom-date">
            <span>To</span>
            <input type="date" [value]="endDate()" (input)="setEndDate($event)" />
          </label>
        }

        <button class="primary-button" type="submit">
          Apply filters
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m9 18 6-6-6-6" /></svg>
        </button>
      </form>

      @if (searchLoading()) {
        <p class="filter-feedback" role="status">Searching…</p>
      } @else if (searchError(); as error) {
        <p class="filter-feedback filter-feedback--error" role="status">{{ error }}</p>
      } @else if (filtersApplied()) {
        <p class="filter-feedback" role="status">Filters applied to the transaction view.</p>
      }
    </section>

    <section class="kpi-section" aria-labelledby="kpi-heading" [attr.aria-busy]="dashboardLoading()">
      <div class="kpi-section__heading">
        <div>
          <p class="eyebrow">Operational snapshot</p>
          <h2 id="kpi-heading">Transactions Overview</h2>
        </div>
        @if (dashboardDetails(); as details) {
          <span>{{ details.fromDate | date:'MMM d' }} – {{ details.toDate | date:'MMM d, y' }}</span>
        }
      </div>

      <div class="kpi-transaction-only">
        <div class="kpi-transaction-group">
        <article class="kpi-card kpi-card--transactions">
          <div class="kpi-card__topline">
            <span>Transaction Totals</span>
            <span class="kpi-card__icon" aria-hidden="true">TX</span>
          </div>
          @if (countryRequired()) {
            <p class="kpi-scope-prompt">Select a country to see transaction totals for it.</p>
          } @else if (dashboardLoading()) {
            <span class="kpi-loading">Loading…</span>
          } @else if (dashboardError()) {
            <strong class="kpi-error">Unavailable</strong>
            <small>{{ dashboardError() }}</small>
          } @else if (dashboardDetails(); as details) {
            <div class="issue-breakdown">
              <div class="issue-kpi issue-kpi--neutral">
                <span>Selected</span>
                <strong>{{ details.transactionOverview.selected | number:'1.0-0' }}</strong>
              </div>
              <div class="issue-kpi issue-kpi--neutral">
                <span>Expected</span>
                <strong>{{ details.transactionOverview.expected | number:'1.0-0' }}</strong>
              </div>
              <button
                type="button"
                class="issue-kpi"
                [disabled]="details.transactionOverview.excluded === 0"
                [attr.aria-label]="'View ' + details.transactionOverview.excluded + ' excluded transactions'"
                (click)="openExcludedTransactionsExplorer()"
              >
                <span>Excluded</span>
                <strong>{{ details.transactionOverview.excluded | number:'1.0-0' }}</strong>
              </button>
              <button
                type="button"
                class="issue-kpi"
                [disabled]="details.transactionOverview.notReported === 0"
                [attr.aria-label]="'View ' + details.transactionOverview.notReported + ' not-reported transactions'"
                (click)="openNotReportedTransactionsExplorer()"
              >
                <span>Not Reported</span>
                <strong>{{ details.transactionOverview.notReported | number:'1.0-0' }}</strong>
              </button>
            </div>

            <div class="transaction-gauges">
              <div class="exclusion-gauge">
                <svg class="exclusion-gauge__svg" viewBox="0 0 220 116" aria-hidden="true">
                  <path class="exclusion-gauge__band exclusion-gauge__band--healthy" d="M20,110 A90,90 0 0 1 46.36,46.36" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--low" d="M46.36,46.36 A90,90 0 0 1 110,20" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--elevated" d="M110,20 A90,90 0 0 1 173.64,46.36" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--high" d="M173.64,46.36 A90,90 0 0 1 200,110" />
                  <line
                    class="exclusion-gauge__needle"
                    x1="110" y1="110"
                    [attr.x2]="gaugeNeedleX(exclusionRatePercent(details.transactionOverview))"
                    [attr.y2]="gaugeNeedleY(exclusionRatePercent(details.transactionOverview))"
                  />
                  <circle class="exclusion-gauge__hub" cx="110" cy="110" r="6" />
                </svg>
                <div class="exclusion-gauge__readout">
                  <strong [style.color]="gaugeZoneColor(exclusionRatePercent(details.transactionOverview))">{{ formatGaugeRate(exclusionRatePercent(details.transactionOverview)) }}</strong>
                  <span>Exclusion Rate</span>
                </div>
              </div>

              <div class="exclusion-gauge">
                <svg class="exclusion-gauge__svg" viewBox="0 0 220 116" aria-hidden="true">
                  <path class="exclusion-gauge__band exclusion-gauge__band--healthy" d="M20,110 A90,90 0 0 1 46.36,46.36" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--low" d="M46.36,46.36 A90,90 0 0 1 110,20" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--elevated" d="M110,20 A90,90 0 0 1 173.64,46.36" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--high" d="M173.64,46.36 A90,90 0 0 1 200,110" />
                  <line
                    class="exclusion-gauge__needle"
                    x1="110" y1="110"
                    [attr.x2]="gaugeNeedleX(notReportedRatePercent(details.transactionOverview))"
                    [attr.y2]="gaugeNeedleY(notReportedRatePercent(details.transactionOverview))"
                  />
                  <circle class="exclusion-gauge__hub" cx="110" cy="110" r="6" />
                </svg>
                <div class="exclusion-gauge__readout">
                  <strong [style.color]="gaugeZoneColor(notReportedRatePercent(details.transactionOverview))">{{ formatGaugeRate(notReportedRatePercent(details.transactionOverview)) }}</strong>
                  <span>Not Reported Rate</span>
                </div>
              </div>
            </div>

            <div class="exclusion-gauge__legend">
              <span><i class="exclusion-gauge__legend-dot exclusion-gauge__legend-dot--healthy"></i>0–1% Healthy</span>
              <span><i class="exclusion-gauge__legend-dot exclusion-gauge__legend-dot--low"></i>1–5% Low</span>
              <span><i class="exclusion-gauge__legend-dot exclusion-gauge__legend-dot--elevated"></i>5–10% Elevated</span>
              <span><i class="exclusion-gauge__legend-dot exclusion-gauge__legend-dot--high"></i>10%+ High</span>
            </div>

            <small class="transaction-overview-note">Reported/excluded status derived from each transaction's full journey history, not batch-level aggregates</small>
          }
        </article>
        </div>

        <div class="kpi-breakdowns-group">
        <article class="kpi-card kpi-card--exclusion-reasons">
        <div class="kpi-card__topline">
          <span>Top Exclusion Reasons</span>
        </div>
        @if (countryRequired()) {
          <p class="kpi-scope-prompt">Select a country to see exclusion reasons for it.</p>
        } @else if (dashboardLoading()) {
          <span class="kpi-loading">Loading…</span>
        } @else if (dashboardError()) {
          <strong class="kpi-error">Unavailable</strong>
        } @else if (dashboardDetails(); as details) {
          @if (details.topExclusionReasons.length === 0) {
            <p class="kpi-scope-prompt">No excluded transactions in this period.</p>
          } @else {
            <div
              class="breakdown-stack"
              role="img"
              [attr.aria-label]="'Exclusion reasons for ' + (details.transactionOverview.excluded | number:'1.0-0') + ' excluded transactions'"
            >
              @for (item of details.topExclusionReasons; track item.reason; let i = $index) {
                <span
                  class="breakdown-stack__segment"
                  [style.flex]="item.count + ' 0 0'"
                  [style.background]="exclusionReasonColor(i)"
                  [attr.title]="humanizeReason(item.reason) + ': ' + (item.count | number:'1.0-0') + ' (' + (exclusionReasonSharePercent(item.count, details.transactionOverview.excluded) | number:'1.0-2') + '%)'"
                ></span>
              }
            </div>
            <ul class="breakdown-legend">
              @for (item of details.topExclusionReasons; track item.reason; let i = $index) {
                <li>
                  <button
                    type="button"
                    class="breakdown-legend__row"
                    [attr.aria-label]="'View ' + item.count + ' excluded transactions for ' + humanizeReason(item.reason)"
                    (click)="openExcludedTransactionsExplorer(item.reason)"
                  >
                    <span class="breakdown-legend__swatch" [style.background]="exclusionReasonColor(i)"></span>
                    <span class="breakdown-legend__label">{{ humanizeReason(item.reason) }}</span>
                    <span class="breakdown-legend__percent">{{ exclusionReasonSharePercent(item.count, details.transactionOverview.excluded) | number:'1.0-2' }}%</span>
                    <span class="breakdown-legend__value">{{ item.count | number:'1.0-0' }}</span>
                  </button>
                </li>
              }
            </ul>
            <small>Reason reflects why each transaction was excluded, based on its full processing history -- not necessarily its most recent status.</small>
          }
        }
      </article>

      <article class="kpi-card kpi-card--not-reported-breakdown">
        <div class="kpi-card__topline">
          <span>Not Reported Breakdown</span>
        </div>
        @if (countryRequired()) {
          <p class="kpi-scope-prompt">Select a country to see the not-reported breakdown for it.</p>
        } @else if (dashboardLoading()) {
          <span class="kpi-loading">Loading…</span>
        } @else if (dashboardError()) {
          <strong class="kpi-error">Unavailable</strong>
        } @else if (dashboardDetails(); as details) {
          @if (details.notReportedReasons.length === 0) {
            <p class="kpi-scope-prompt">No not-reported transactions in this period.</p>
          } @else {
            <div
              class="breakdown-stack"
              role="img"
              [attr.aria-label]="'Not-reported breakdown for ' + (details.transactionOverview.notReported | number:'1.0-0') + ' not-reported transactions'"
            >
              @for (item of details.notReportedReasons; track item.reason; let i = $index) {
                <span
                  class="breakdown-stack__segment"
                  [style.flex]="item.count + ' 0 0'"
                  [style.background]="notReportedReasonColor(i)"
                  [attr.title]="humanizeReason(item.reason) + ': ' + (item.count | number:'1.0-0') + ' (' + (exclusionReasonSharePercent(item.count, details.transactionOverview.notReported) | number:'1.0-2') + '%)'"
                ></span>
              }
            </div>
            <ul class="breakdown-legend">
              @for (item of details.notReportedReasons; track item.reason; let i = $index) {
                <li>
                  <button
                    type="button"
                    class="breakdown-legend__row"
                    [attr.aria-label]="'View ' + item.count + ' not-reported transactions for ' + humanizeReason(item.reason)"
                    (click)="openNotReportedTransactionsExplorer(item.reason)"
                  >
                    <span class="breakdown-legend__swatch" [style.background]="notReportedReasonColor(i)"></span>
                    <span class="breakdown-legend__label">{{ humanizeReason(item.reason) }}</span>
                    <span class="breakdown-legend__percent">{{ exclusionReasonSharePercent(item.count, details.transactionOverview.notReported) | number:'1.0-2' }}%</span>
                    <span class="breakdown-legend__value">{{ item.count | number:'1.0-0' }}</span>
                  </button>
                </li>
              }
            </ul>
            <small>Reason reflects why each transaction hasn't been reported, based on its full processing history -- not necessarily its most recent status.</small>
          }
        }
        </article>
        </div>
      </div>
    </section>

    <section class="operational-trends-section" aria-labelledby="operational-trends-heading">
      <p class="eyebrow" id="operational-trends-heading">Operational trend</p>

      <div class="issue-trend-section" aria-labelledby="excluded-trend-heading">
      <div class="issue-trend-heading">
        <div>
          <h2 id="excluded-trend-heading">{{ excludedTransactionsTrendTitle(dashboardDetails()?.trendGranularity) }}</h2>
          <p>Cell intensity shows relative volume for reported, and excluded share of that day's transactions for excluded; labels show exact counts either way.</p>
        </div>
        <div class="heatmap-legend" aria-label="Heatmap intensity legend">
          <span>Lower</span><i></i><span>Higher impact</span>
        </div>
      </div>

      <div class="issue-trend-card">
        @if (countryRequired()) {
          <div class="chart-message">Select a country to see the daily transaction totals.</div>
        } @else if (dashboardLoading()) {
          <div class="chart-message">Loading transaction totals…</div>
        } @else if (dashboardError()) {
          <div class="chart-message chart-message--error">Transaction-totals data is unavailable.</div>
        } @else if (dashboardDetails(); as details) {
          <div class="heatmap-scroll">
            <div
              class="issue-heatmap"
              role="table"
              aria-label="Transaction totals by reporting period"
              [style.min-width.px]="heatmapMinimumWidth(details.batchHealthTrend.length, details.trendGranularity)"
              [style.grid-template-columns]="'180px repeat(' + details.batchHealthTrend.length + ', minmax(38px, 1fr))'"
            >
              <div class="heatmap-corner" role="columnheader">Metric</div>
              @for (period of details.batchHealthTrend; track period.periodStart) {
                <div class="heatmap-period" role="columnheader" [attr.title]="period.periodStart + ' to ' + period.periodEnd">
                  {{ period.periodStart | date:trendDateFormat(details.trendGranularity):'UTC' }}
                </div>
              }

              <div class="heatmap-row-label" role="rowheader"><span></span>Reported transactions</div>
              @for (period of details.batchHealthTrend; track period.periodStart) {
                <div
                  class="heatmap-cell"
                  role="cell"
                  [style.background]="reportedHeatmapCellColor(period.totalReportedTransactions, trendReportedTransactionsMaximum(details.batchHealthTrend))"
                  [style.color]="reportedHeatmapTextColor(period.totalReportedTransactions, trendReportedTransactionsMaximum(details.batchHealthTrend))"
                >
                  <button
                    type="button"
                    class="heatmap-cell-button"
                    [disabled]="period.totalReportedTransactions === 0"
                    [attr.title]="reportedTransactionsCellTitle(period)"
                    [attr.aria-label]="reportedTransactionsCellTitle(period)"
                    (click)="openPeriodTransactionExplorer(period, 'REPORTED')"
                  >{{ period.totalReportedTransactions | number:'1.0-0' }}</button>
                </div>
              }

              <div class="heatmap-row-label" role="rowheader"><span></span>Excluded transactions</div>
              @for (period of details.batchHealthTrend; track period.periodStart) {
                <div
                  class="heatmap-cell"
                  role="cell"
                  [style.background]="excludedHeatmapCellColor(period.totalExcludedTransactions, period.totalReportedTransactions + period.totalExcludedTransactions)"
                  [style.color]="excludedHeatmapTextColor(period.totalExcludedTransactions, period.totalReportedTransactions + period.totalExcludedTransactions)"
                >
                  <button
                    type="button"
                    class="heatmap-cell-button"
                    [disabled]="period.totalExcludedTransactions === 0"
                    [attr.title]="excludedTransactionsCellTitle(period)"
                    [attr.aria-label]="excludedTransactionsCellTitle(period)"
                    (click)="openPeriodTransactionExplorer(period, 'EXCLUDED')"
                  >{{ period.totalExcludedTransactions | number:'1.0-0' }}</button>
                </div>
              }
            </div>
          </div>
        }
      </div>
      </div>

      <div class="issue-trend-section" aria-labelledby="reported-trend-heading">
        <div class="issue-trend-heading">
          <div>
            <h2 id="reported-trend-heading">{{ reportedTransactionsTrendTitle(dashboardDetails()?.trendGranularity) }}</h2>
            <p>Same periods as the totals above -- point count matches each period's own reported-transactions tile.</p>
          </div>
        </div>

        <div class="issue-trend-card" #trendCardHost>
          @if (countryRequired()) {
            <div class="chart-message">Select a country to see the reported transactions trend.</div>
          } @else if (dashboardLoading()) {
            <div class="chart-message">Loading reported transactions…</div>
          } @else if (dashboardError()) {
            <div class="chart-message chart-message--error">Reported-transactions trend is unavailable.</div>
          } @else if (dashboardDetails(); as details) {
            @if (details.batchHealthTrend.length === 0) {
              <div class="chart-message">No reported transactions in this period.</div>
            } @else {
              <div class="trend-line-chart-scroll">
                <svg
                  class="trend-line-chart"
                  [attr.viewBox]="'0 0 ' + trendChartWidth(details.batchHealthTrend.length, details.trendGranularity) + ' 220'"
                  [style.width.px]="trendChartWidth(details.batchHealthTrend.length, details.trendGranularity)"
                  role="img"
                  [attr.aria-label]="reportedTransactionsTrendTitle(details.trendGranularity) + ' chart'"
                >
                  @for (line of reportedTrendGridlines(details.batchHealthTrend); track line.value) {
                    <line
                      class="trend-line-chart__gridline"
                      x1="0"
                      [attr.x2]="trendChartWidth(details.batchHealthTrend.length, details.trendGranularity)"
                      [attr.y1]="line.y"
                      [attr.y2]="line.y"
                    ></line>
                    <text class="trend-line-chart__axis-label" x="2" [attr.y]="line.y - 4">{{ line.value | number:'1.0-0' }}</text>
                  }

                  <path
                    class="trend-line-chart__area"
                    [attr.d]="reportedTrendAreaPath(details.batchHealthTrend, trendChartWidth(details.batchHealthTrend.length, details.trendGranularity))"
                  ></path>
                  <path
                    class="trend-line-chart__line"
                    [attr.d]="reportedTrendLinePath(details.batchHealthTrend, trendChartWidth(details.batchHealthTrend.length, details.trendGranularity))"
                  ></path>

                  @for (point of reportedTrendPoints(details.batchHealthTrend, trendChartWidth(details.batchHealthTrend.length, details.trendGranularity)); track point.period.periodStart) {
                    <text class="trend-line-chart__x-label" [attr.x]="point.x" y="212">{{ point.period.periodStart | date:trendDateFormat(details.trendGranularity):'UTC' }}</text>
                    <g
                      class="trend-line-chart__point-group"
                      [class.trend-line-chart__point-group--zero]="point.period.totalReportedTransactions === 0"
                      [attr.aria-label]="reportedTransactionsCellTitle(point.period)"
                      (click)="openReportedTrendPoint(point.period)"
                    >
                      <circle class="trend-line-chart__hit" [attr.cx]="point.x" [attr.cy]="point.y" r="10"></circle>
                      <circle class="trend-line-chart__point" [attr.cx]="point.x" [attr.cy]="point.y" r="3.5"></circle>
                      <title>{{ reportedTransactionsCellTitle(point.period) }}</title>
                    </g>
                  }
                </svg>
              </div>
            }
          }
        </div>
      </div>

      <div class="issue-trend-section" aria-labelledby="excluded-line-trend-heading">
        <div class="issue-trend-heading">
          <div>
            <h2 id="excluded-line-trend-heading">{{ excludedLineTrendTitle(dashboardDetails()?.trendGranularity) }}</h2>
            <p>Same periods as the totals above -- point count matches each period's own excluded-transactions tile.</p>
          </div>
        </div>

        <div class="issue-trend-card">
          @if (countryRequired()) {
            <div class="chart-message">Select a country to see the excluded transactions trend.</div>
          } @else if (dashboardLoading()) {
            <div class="chart-message">Loading excluded transactions…</div>
          } @else if (dashboardError()) {
            <div class="chart-message chart-message--error">Excluded-transactions trend is unavailable.</div>
          } @else if (dashboardDetails(); as details) {
            @if (details.batchHealthTrend.length === 0) {
              <div class="chart-message">No excluded transactions in this period.</div>
            } @else {
              <div class="trend-line-chart-scroll">
                <svg
                  class="trend-line-chart trend-line-chart--excluded"
                  [attr.viewBox]="'0 0 ' + trendChartWidth(details.batchHealthTrend.length, details.trendGranularity) + ' 220'"
                  [style.width.px]="trendChartWidth(details.batchHealthTrend.length, details.trendGranularity)"
                  role="img"
                  [attr.aria-label]="excludedLineTrendTitle(details.trendGranularity) + ' chart'"
                >
                  @for (line of excludedTrendGridlines(details.batchHealthTrend); track line.value) {
                    <line
                      class="trend-line-chart__gridline"
                      x1="0"
                      [attr.x2]="trendChartWidth(details.batchHealthTrend.length, details.trendGranularity)"
                      [attr.y1]="line.y"
                      [attr.y2]="line.y"
                    ></line>
                    <text class="trend-line-chart__axis-label" x="2" [attr.y]="line.y - 4">{{ line.value | number:'1.0-0' }}</text>
                  }

                  <path
                    class="trend-line-chart__area"
                    [attr.d]="excludedTrendAreaPath(details.batchHealthTrend, trendChartWidth(details.batchHealthTrend.length, details.trendGranularity))"
                  ></path>
                  <path
                    class="trend-line-chart__line"
                    [attr.d]="excludedTrendLinePath(details.batchHealthTrend, trendChartWidth(details.batchHealthTrend.length, details.trendGranularity))"
                  ></path>

                  @for (point of excludedTrendPoints(details.batchHealthTrend, trendChartWidth(details.batchHealthTrend.length, details.trendGranularity)); track point.period.periodStart) {
                    <text class="trend-line-chart__x-label" [attr.x]="point.x" y="212">{{ point.period.periodStart | date:trendDateFormat(details.trendGranularity):'UTC' }}</text>
                    <g
                      class="trend-line-chart__point-group"
                      [class.trend-line-chart__point-group--zero]="point.period.totalExcludedTransactions === 0"
                      [attr.aria-label]="excludedTransactionsCellTitle(point.period)"
                      (click)="openExcludedTrendPoint(point.period)"
                    >
                      <circle class="trend-line-chart__hit" [attr.cx]="point.x" [attr.cy]="point.y" r="10"></circle>
                      <circle class="trend-line-chart__point" [attr.cx]="point.x" [attr.cy]="point.y" r="3.5"></circle>
                      <title>{{ excludedTransactionsCellTitle(point.period) }}</title>
                    </g>
                  }
                </svg>
              </div>
            }
          }
        </div>
      </div>
    </section>
  `
})
export class TransactionOverviewComponent implements OnInit, AfterViewInit, OnDestroy {
  readonly country = signal('ALL');
  readonly reportPeriod = signal<ReportPeriod>('LAST_7_DAYS');
  readonly startDate = signal('');
  readonly endDate = signal('');
  readonly filtersApplied = signal(false);
  readonly dashboardDetails = signal<TransactionDashboardResponse | null>(null);
  readonly dashboardLoading = signal(false);
  readonly dashboardError = signal<string | null>(null);
  readonly countryOptions = signal<CountryOption[]>([]);
  readonly reportGroupId = signal('ALL');
  readonly reportGroupOptions = signal<ReportGroupOption[]>([]);

  readonly searchField = signal<TransactionSearchField>('MTCN');
  readonly searchQuery = signal('');
  readonly searchLoading = signal(false);
  readonly searchError = signal<string | null>(null);
  // Mirrors the dropdown's own option label exactly rather than restating the field name in a
  // longer phrase ("Enter external transaction key") -- that phrase doesn't fit the input's
  // remaining width once the dropdown itself takes its share, so it got hard-clipped instead of
  // wrapping or eliding.
  readonly searchPlaceholder = computed(() => (this.searchField() === 'MTCN' ? 'MTCN' : 'External Txn ID'));

  /** Measured live from the card's own content box (see {@link ngAfterViewInit}) so the line
   *  chart's SVG can render at that exact pixel width -- a 1:1 viewBox-to-pixel mapping, with no
   *  CSS stretch/`preserveAspectRatio` scaling involved. Stretching a non-square viewBox to fill a
   *  wider container (the earlier approach) scaled the x and y axes by different factors, which
   *  visibly distorted the axis-label text into a stretched/squashed look on any window size where
   *  that mismatch was large. The default here is only what's visible before the first
   *  ResizeObserver callback fires (typically the same frame). */
  readonly trendChartContainerWidth = signal(680);

  @ViewChild('trendCardHost') private trendCardHost?: ElementRef<HTMLDivElement>;
  private trendChartResizeObserver?: ResizeObserver;

  private readonly filterState = inject(DashboardFilterStateService);

  constructor(
    private readonly http: HttpClient,
    private readonly router: Router,
    private readonly route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.restoreRouteFilters();
    this.http.get<BatchFilterOptionsResponse>('/api/v1/batches/filter-options').subscribe({
      next: options => this.countryOptions.set(options.countries),
      error: () => this.countryOptions.set([])
    });
    this.http.get<ReportConfigExplorerResponse>('/api/v1/report-configs').subscribe({
      next: response =>
        this.reportGroupOptions.set(
          response.configurations
            .map(config => ({
              reportGroupId: config.reportGroupId,
              reportGroupName: config.reportGroupName,
              countryCode: config.countryCode
            }))
            .sort((a, b) => (a.reportGroupName || '').localeCompare(b.reportGroupName || ''))
        ),
      error: () => this.reportGroupOptions.set([])
    });
    this.loadDashboardDetails();
  }

  /** ResizeObserver's contentRect is always the content box (padding excluded) regardless of
   *  box-sizing, and .trend-line-chart-scroll adds no horizontal padding of its own -- so this is
   *  exactly the width available to the chart, no manual padding math needed. Guarded for
   *  environments without ResizeObserver (e.g. some test runners); the chart just keeps its
   *  fallback width there instead of resizing live. */
  ngAfterViewInit(): void {
    const element = this.trendCardHost?.nativeElement;
    if (!element || typeof ResizeObserver === 'undefined') {
      return;
    }
    this.trendChartResizeObserver = new ResizeObserver(entries => {
      const width = entries[0]?.contentRect.width;
      if (width) {
        this.trendChartContainerWidth.set(Math.round(width));
      }
    });
    this.trendChartResizeObserver.observe(element);
  }

  ngOnDestroy(): void {
    this.trendChartResizeObserver?.disconnect();
  }

  setSearchQuery(event: Event): void {
    this.searchQuery.set((event.target as HTMLInputElement).value);
  }

  setSearchField(event: Event): void {
    this.searchField.set((event.target as HTMLSelectElement).value as TransactionSearchField);
  }

  /** Deliberately unscoped -- no date range, country, or report group required, since the whole
   *  point is finding a transaction when none of those are known yet. Resolves via
   *  TransactionSearchRepository, scoped to exactly the field the dropdown picked (MTCN or
   *  external transaction key -- no more guessing which field an ambiguous value was meant to
   *  match), then redirects straight to the transaction report's own detailed view instead of
   *  rendering a second results table here -- redirecting on the resolved mtcn rather than the
   *  raw typed text, since that page's own search only matches identifier/mtcn and wouldn't find
   *  anything if the user had searched by external transaction key. Never logs the query value
   *  itself (only its length, server-side), matching the app's convention for search terms over
   *  customer transaction data.
   *
   *  Triggered by Enter in the search field rather than its own submit button, since it shares a
   *  row (and a <form>) with the Apply-filters fields -- preventDefault stops that keypress from
   *  also submitting the form as an Apply-filters action. */
  runSearch(event: Event): void {
    event.preventDefault();
    if (this.searchLoading()) {
      return;
    }
    const query = this.searchQuery().trim();
    if (!query) {
      return;
    }
    this.searchLoading.set(true);
    this.searchError.set(null);
    this.http
      .get<TransactionSearchResponse>('/api/v1/transactions/search', {
        params: new HttpParams().set('field', this.searchField()).set('query', query)
      })
      .subscribe({
        next: response => {
          this.searchLoading.set(false);
          const mtcn = response.results.find(result => result.mtcn)?.mtcn;
          if (!mtcn) {
            this.searchError.set(`No evidence found for "${query}".`);
            return;
          }
          void this.router.navigate(['/transactions'], {
            queryParams: { fromDate: '2000-01-01', toDate: '2099-12-31', search: mtcn }
          });
        },
        error: () => {
          this.searchLoading.set(false);
          this.searchError.set('Could not complete the search. Please try again.');
        }
      });
  }

  /** Country is a required field on this page (unlike Batch View) -- 'ALL' means "nothing picked
   *  yet," not "show every country's numbers blended together," since that blend is exactly the
   *  cross-country/cross-rule-side double-counting problem this whole page exists to avoid. */
  readonly countryRequired = computed(() => this.country() === 'ALL');

  /** Same country<->report-group mutual filtering as batch-explorer.component.ts and
   *  home.component.ts -- kept as its own copy here rather than shared, since this page's filter
   *  requirements are expected to diverge from Batch View's once the report-group-scoping
   *  decision from the Rajat conversation lands (see project notes on ever_reported/ever_excluded
   *  grain). Duplicating three small filter methods now is cheaper than building a shared
   *  abstraction for a contract that's about to change anyway. */
  readonly filteredReportGroupOptions = computed(() => {
    const country = this.country();
    return country === 'ALL'
      ? this.reportGroupOptions()
      : this.reportGroupOptions().filter(option => option.countryCode === country);
  });

  setCountryValue(country: string): void {
    this.country.set(country);
    this.filtersApplied.set(false);
    const selectedReportGroupId = this.reportGroupId();
    if (selectedReportGroupId !== 'ALL' && country !== 'ALL') {
      const stillValid = this.reportGroupOptions().some(
        option => String(option.reportGroupId) === selectedReportGroupId && option.countryCode === country
      );
      if (!stillValid) {
        this.reportGroupId.set('ALL');
      }
    }
  }

  setReportGroupValue(reportGroupId: string): void {
    this.reportGroupId.set(reportGroupId);
    this.filtersApplied.set(false);
    if (reportGroupId !== 'ALL') {
      const selected = this.reportGroupOptions().find(option => String(option.reportGroupId) === reportGroupId);
      if (selected) {
        this.country.set(selected.countryCode);
      }
    }
  }

  setReportPeriod(event: Event): void {
    this.reportPeriod.set((event.target as HTMLSelectElement).value as ReportPeriod);
    this.filtersApplied.set(false);
  }

  setStartDate(event: Event): void {
    this.startDate.set((event.target as HTMLInputElement).value);
  }

  setEndDate(event: Event): void {
    this.endDate.set((event.target as HTMLInputElement).value);
  }

  applyFilters(event: SubmitEvent): void {
    event.preventDefault();
    this.filtersApplied.set(true);
    this.persistRouteFilters();
    this.loadDashboardDetails();
  }

  resetFilters(): void {
    this.country.set('ALL');
    this.reportGroupId.set('ALL');
    this.reportPeriod.set('LAST_7_DAYS');
    this.startDate.set('');
    this.endDate.set('');
    this.filtersApplied.set(false);
    this.persistRouteFilters();
    this.loadDashboardDetails();
  }

  /** `reason` narrows to one Top Exclusion Reasons legend row -- the raw (un-humanized) value from
   *  ExclusionReasonResponse, matched server-side against the exact same full-journey-history
   *  reason bucket TransactionReportRepository#reportingTarget computes, so the drill-through's row
   *  count matches the legend's count exactly. */
  openExcludedTransactionsExplorer(reason?: string): void {
    const period = this.resolvePeriod();
    if (!period) {
      this.dashboardError.set('Select both custom dates.');
      return;
    }
    void this.router.navigate(['/transactions'], {
      queryParams: {
        fromDate: period.fromDate,
        toDate: period.toDate,
        country: this.country(),
        reportGroupId: this.selectedReportGroupIdOrNull(),
        status: 'EXCLUDED',
        reason: reason || null,
        view: 'overview',
        origin: 'overview'
      }
    });
  }

  /** `reason` narrows to one Not Reported Reasons legend row, matched server-side against the same
   *  full-journey-history category TransactionReportRepository#notReportedReason computes, so the
   *  drill-through's row count matches the legend's count exactly. */
  openNotReportedTransactionsExplorer(reason?: string): void {
    const period = this.resolvePeriod();
    if (!period) {
      this.dashboardError.set('Select both custom dates.');
      return;
    }
    void this.router.navigate(['/transactions'], {
      queryParams: {
        fromDate: period.fromDate,
        toDate: period.toDate,
        country: this.country(),
        reportGroupId: this.selectedReportGroupIdOrNull(),
        status: 'NOT_REPORTED',
        reason: reason || null,
        view: 'overview',
        origin: 'overview'
      }
    });
  }

  openPeriodTransactionExplorer(period: BatchHealthTrend, metricFocus: 'REPORTED' | 'EXCLUDED'): void {
    void this.router.navigate(['/transactions'], {
      queryParams: {
        fromDate: period.periodStart,
        toDate: period.periodEnd,
        country: this.country(),
        reportGroupId: this.selectedReportGroupIdOrNull(),
        status: metricFocus,
        // Distinct from `view: 'overview'` (openExcludedTransactionsExplorer/
        // openNotReportedTransactionsExplorer) -- that flag also restricts the report's status
        // dropdown to EXCLUDED/NOT_REPORTED, which is wrong here since a REPORTED total still needs
        // the full dropdown. `origin` only tells the report page where "Back to dashboard" should
        // return to, independent of that restriction.
        origin: 'overview'
      }
    });
  }

  exclusionRatePercent(overview: TransactionOverview): number {
    return overview.selected === 0 ? 0 : (overview.excluded / overview.selected) * 100;
  }

  notReportedRatePercent(overview: TransactionOverview): number {
    return overview.expected === 0 ? 0 : (overview.notReported / overview.expected) * 100;
  }

  gaugeNeedleAngle(ratePercent: number): number {
    const zones = [
      { min: 0, max: 1, angleStart: 0, angleEnd: 45 },
      { min: 1, max: 5, angleStart: 45, angleEnd: 90 },
      { min: 5, max: 10, angleStart: 90, angleEnd: 135 },
      { min: 10, max: Infinity, angleStart: 135, angleEnd: 180 }
    ];
    const clamped = Math.max(0, ratePercent);
    for (const zone of zones) {
      if (clamped < zone.max) {
        const span = zone.max === Infinity ? 10 : zone.max - zone.min;
        const progress = Math.min(1, (clamped - zone.min) / span);
        return zone.angleStart + progress * (zone.angleEnd - zone.angleStart);
      }
    }
    return 180;
  }

  private gaugeNeedlePoint(ratePercent: number): { x: number; y: number } {
    const radians = (this.gaugeNeedleAngle(ratePercent) * Math.PI) / 180;
    const radius = 68;
    return { x: 110 - radius * Math.cos(radians), y: 110 - radius * Math.sin(radians) };
  }

  gaugeNeedleX(ratePercent: number): number {
    return this.gaugeNeedlePoint(ratePercent).x;
  }

  gaugeNeedleY(ratePercent: number): number {
    return this.gaugeNeedlePoint(ratePercent).y;
  }

  gaugeZoneColor(ratePercent: number): string {
    if (ratePercent < 1) {
      return '#547563';
    }
    if (ratePercent < 5) {
      return '#8a7d3f';
    }
    if (ratePercent < 10) {
      return '#a05f2e';
    }
    return '#bd343e';
  }

  formatGaugeRate(ratePercent: number): string {
    return `${ratePercent.toFixed(2)}%`;
  }

  trendReportedTransactionsMaximum(periods: BatchHealthTrend[]): number {
    return Math.max(1, ...periods.map(period => period.totalReportedTransactions));
  }

  /** Exact share, unrounded -- rendered with the template's `number:'1.0-2'` (not the usual
   *  '1.0-0'), since a whole-number display made a genuinely 99.9%/0.1% split print as a misleading
   *  "100%"/"0%" for a category that still had real transactions in it. */
  exclusionReasonSharePercent(count: number, totalExcluded: number): number {
    return totalExcluded === 0 ? 0 : (count / totalExcluded) * 100;
  }

  /** The dataviz skill's validated default categorical palette (see references/palette.md) --
   *  eight hues in a fixed order that clear CVD-safety checks for adjacent marks (a stacked bar's
   *  touching segments), assigned by position since exclusion reasons are nominal categories with
   *  no persistent cross-scope identity (the top reason for Portugal isn't "always slot 1" in any
   *  meaningful sense -- each chart instance pairs its own swatches with its own legend). Capped at
   *  8 server-side, matching this palette's slot count. */
  private static readonly EXCLUSION_REASON_PALETTE = [
    '#2a78d6', // blue
    '#eb6834', // orange
    '#1baf7a', // aqua
    '#eda100', // yellow
    '#e87ba4', // magenta
    '#008300', // green
    '#4a3aa7', // violet
    '#e34948' // red
  ];

  exclusionReasonColor(index: number): string {
    return TransactionOverviewComponent.EXCLUSION_REASON_PALETTE[index % TransactionOverviewComponent.EXCLUSION_REASON_PALETTE.length];
  }

  /** Not Reported Reasons reuses the same healthy/low/elevated/high status-band colors as this
   *  card's own Exclusion Rate / Not Reported Rate gauges (.exclusion-gauge__band--*) instead of
   *  the generic categorical palette, so the two color languages on this page stay in sync rather
   *  than introducing a second, unrelated hue set. */
  private static readonly NOT_REPORTED_REASON_PALETTE = [
    '#6f927f', // healthy
    '#b8ab72', // low
    '#c08c5b', // elevated
    '#c4474f' // high (--failure-red)
  ];

  notReportedReasonColor(index: number): string {
    return TransactionOverviewComponent.NOT_REPORTED_REASON_PALETTE[
      index % TransactionOverviewComponent.NOT_REPORTED_REASON_PALETTE.length
    ];
  }

  /** Same SCREAMING_SNAKE_CASE -> "Screaming Snake Case" humanization as
   *  transaction-report.component.ts's humanizeIfCode -- only applied to machine-constant-shaped
   *  reasons (skip_reason), leaving already-readable free-text ones (a configured-strategy
   *  sentence, typically from comments) untouched. */
  humanizeReason(value: string): string {
    if (!/^[A-Z0-9_()./-]+$/.test(value)) {
      return value;
    }
    return value
      .replace(/_/g, ' ')
      .replace(/\(/g, ' (')
      .trim()
      .split(/\s+/)
      .map(word => {
        const match = word.match(/^(\()?(.*?)(\))?$/);
        if (!match || !match[2]) {
          return word;
        }
        const [, open, core, close] = match;
        const lower = core.toLowerCase();
        return `${open ?? ''}${lower.charAt(0).toUpperCase()}${lower.slice(1)}${close ?? ''}`;
      })
      .join(' ');
  }

  reportedHeatmapCellColor(reportedCount: number, maximum: number): string {
    if (maximum === 0) {
      return '#efeee9';
    }
    if (reportedCount === 0) {
      return '#f9f5f5';
    }
    const impact = reportedCount / maximum;
    return `rgba(111, 146, 127, ${0.14 + impact * 0.66})`;
  }

  reportedHeatmapTextColor(reportedCount: number, maximum: number): string {
    const impact = maximum === 0 ? 0 : reportedCount / maximum;
    return impact >= 0.7 ? '#ffffff' : reportedCount === 0 ? '#aaa9a1' : '#2f4a3b';
  }

  reportedTransactionsCellTitle(period: BatchHealthTrend): string {
    return `Reported transactions: ${period.totalReportedTransactions} from ${period.periodStart} to ${period.periodEnd}`;
  }

  excludedTransactionsTrendTitle(granularity: TrendGranularity | undefined): string {
    if (!granularity) {
      return 'Transaction Totals Trend';
    }
    return `${granularity.charAt(0)}${granularity.slice(1).toLowerCase()} Transaction Totals`;
  }

  trendDateFormat(granularity: TrendGranularity): string {
    return granularity === 'MONTHLY' ? 'MMM' : 'MMM d';
  }

  heatmapMinimumWidth(periodCount: number, granularity: TrendGranularity): number {
    const bucketWidth = granularity === 'DAILY' ? 42 : granularity === 'WEEKLY' ? 64 : 78;
    return Math.max(760, 180 + periodCount * bucketWidth);
  }

  /** Same per-period bucket widths as {@link heatmapMinimumWidth} (so a daily/weekly/monthly view
   *  keeps a consistent, already-proven-legible column width across both this chart and the
   *  heatmap above it) -- just without that table's 180px metric-label column, since this chart's
   *  y-axis labels live inside the plot area's own left padding instead. */
  private static readonly TREND_CHART_HEIGHT = 220;
  private static readonly TREND_CHART_PAD_LEFT = 36;
  // Wide enough that the last point's centered x-axis label (e.g. "AUG 31", the longest a daily
  // "MMM d" format produces) doesn't clip past the chart's right edge -- 14px only cleared shorter
  // labels like "AUG 1".
  private static readonly TREND_CHART_PAD_RIGHT = 26;
  private static readonly TREND_CHART_PAD_TOP = 14;
  private static readonly TREND_CHART_PAD_BOTTOM = 34;

  reportedTransactionsTrendTitle(granularity: TrendGranularity | undefined): string {
    if (!granularity) {
      return 'Reported Transactions Trend';
    }
    return `${granularity.charAt(0)}${granularity.slice(1).toLowerCase()} Reported Transactions`;
  }

  excludedLineTrendTitle(granularity: TrendGranularity | undefined): string {
    if (!granularity) {
      return 'Excluded Transactions Trend';
    }
    return `${granularity.charAt(0)}${granularity.slice(1).toLowerCase()} Excluded Transactions`;
  }

  /** Floored at the measured container width (see {@link trendChartContainerWidth}) so a short
   *  range's chart fills the whole card instead of sitting in a small box with blank space beside
   *  it, and floored separately at each period's own minimum legible bucket width so a long daily
   *  range scrolls instead of cramming points closer than that once it would no longer fit even the
   *  full container. Both floors, plus the fixed 220 viewBox height, are rendered 1:1 to actual
   *  pixels (see the template's [style.width.px]) -- never stretched -- which is what keeps the
   *  axis-label text and stroke widths crisp at every size instead of visibly distorting. */
  trendChartWidth(periodCount: number, granularity: TrendGranularity): number {
    const bucketWidth = granularity === 'DAILY' ? 42 : granularity === 'WEEKLY' ? 64 : 78;
    const { TREND_CHART_PAD_LEFT, TREND_CHART_PAD_RIGHT } = TransactionOverviewComponent;
    const naturalWidth = TREND_CHART_PAD_LEFT + TREND_CHART_PAD_RIGHT + periodCount * bucketWidth;
    return Math.max(this.trendChartContainerWidth(), naturalWidth, 640);
  }

  private trendPointX(index: number, count: number, width: number): number {
    const { TREND_CHART_PAD_LEFT, TREND_CHART_PAD_RIGHT } = TransactionOverviewComponent;
    const innerWidth = width - TREND_CHART_PAD_LEFT - TREND_CHART_PAD_RIGHT;
    return count <= 1 ? TREND_CHART_PAD_LEFT + innerWidth / 2 : TREND_CHART_PAD_LEFT + (innerWidth * index) / (count - 1);
  }

  private trendPointY(value: number, maximum: number): number {
    const { TREND_CHART_HEIGHT, TREND_CHART_PAD_TOP, TREND_CHART_PAD_BOTTOM } = TransactionOverviewComponent;
    const innerHeight = TREND_CHART_HEIGHT - TREND_CHART_PAD_TOP - TREND_CHART_PAD_BOTTOM;
    return maximum <= 0
      ? TREND_CHART_HEIGHT - TREND_CHART_PAD_BOTTOM
      : TREND_CHART_HEIGHT - TREND_CHART_PAD_BOTTOM - (innerHeight * value) / maximum;
  }

  /** Unlike {@link trendReportedTransactionsMaximum} (floored at 1 so the heatmap's own
   *  count/maximum ratio never divides by zero), this chart's own {@link trendPointY} already
   *  treats a zero maximum as "everything sits on the baseline" -- flooring here too would instead
   *  make a genuinely all-zero period compute a fake 50%/100% gridline split (see {@link
   *  reportedTrendGridlines}), rendering two gridlines that both round to the same misleading "1". */
  private reportedTrendMaximum(periods: BatchHealthTrend[]): number {
    return Math.max(0, ...periods.map(period => period.totalReportedTransactions));
  }

  reportedTrendPoints(periods: BatchHealthTrend[], width: number): { x: number; y: number; period: BatchHealthTrend }[] {
    const maximum = this.reportedTrendMaximum(periods);
    return periods.map((period, index) => ({
      x: this.trendPointX(index, periods.length, width),
      y: this.trendPointY(period.totalReportedTransactions, maximum),
      period
    }));
  }

  reportedTrendLinePath(periods: BatchHealthTrend[], width: number): string {
    return this.reportedTrendPoints(periods, width)
      .map((point, i) => `${i === 0 ? 'M' : 'L'}${point.x.toFixed(1)},${point.y.toFixed(1)}`)
      .join(' ');
  }

  /** Same line, closed down to the baseline and back to the first point -- a light fill under the
   *  line makes the trend's shape easier to read at a glance than the stroke alone, especially once
   *  a daily view has 20+ points packed into a scrollable width. */
  reportedTrendAreaPath(periods: BatchHealthTrend[], width: number): string {
    const points = this.reportedTrendPoints(periods, width);
    if (points.length === 0) {
      return '';
    }
    const baseline = TransactionOverviewComponent.TREND_CHART_HEIGHT - TransactionOverviewComponent.TREND_CHART_PAD_BOTTOM;
    const line = points.map((point, i) => `${i === 0 ? 'M' : 'L'}${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' ');
    const first = points[0];
    const last = points[points.length - 1];
    return `${line} L${last.x.toFixed(1)},${baseline} L${first.x.toFixed(1)},${baseline} Z`;
  }

  /** Three reference lines (0%, 50%, 100% of the same maximum the line itself is scaled against) --
   *  enough to judge relative height at a glance without the grid competing with the data line.
   *  Collapses to a single "0" line when every period in scope is zero, rather than three lines
   *  that would all land on the same baseline y and round to the same (misleading) label. */
  reportedTrendGridlines(periods: BatchHealthTrend[]): { y: number; value: number }[] {
    const maximum = this.reportedTrendMaximum(periods);
    if (maximum === 0) {
      return [{ y: this.trendPointY(0, 0), value: 0 }];
    }
    return [0, 0.5, 1].map(fraction => ({ y: this.trendPointY(maximum * fraction, maximum), value: Math.round(maximum * fraction) }));
  }

  /** Guards the zero-count case the same way the heatmap's own cell buttons do ([disabled] there) --
   *  an empty period has nothing to drill into. */
  openReportedTrendPoint(period: BatchHealthTrend): void {
    if (period.totalReportedTransactions === 0) {
      return;
    }
    this.openPeriodTransactionExplorer(period, 'REPORTED');
  }

  private excludedTrendMaximum(periods: BatchHealthTrend[]): number {
    return Math.max(0, ...periods.map(period => period.totalExcludedTransactions));
  }

  excludedTrendPoints(periods: BatchHealthTrend[], width: number): { x: number; y: number; period: BatchHealthTrend }[] {
    const maximum = this.excludedTrendMaximum(periods);
    return periods.map((period, index) => ({
      x: this.trendPointX(index, periods.length, width),
      y: this.trendPointY(period.totalExcludedTransactions, maximum),
      period
    }));
  }

  excludedTrendLinePath(periods: BatchHealthTrend[], width: number): string {
    return this.excludedTrendPoints(periods, width)
      .map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x.toFixed(1)},${point.y.toFixed(1)}`)
      .join(' ');
  }

  excludedTrendAreaPath(periods: BatchHealthTrend[], width: number): string {
    const points = this.excludedTrendPoints(periods, width);
    if (points.length === 0) {
      return '';
    }
    const baseline = TransactionOverviewComponent.TREND_CHART_HEIGHT - TransactionOverviewComponent.TREND_CHART_PAD_BOTTOM;
    const line = points.map((point, index) => `${index === 0 ? 'M' : 'L'}${point.x.toFixed(1)},${point.y.toFixed(1)}`).join(' ');
    const first = points[0];
    const last = points[points.length - 1];
    return `${line} L${last.x.toFixed(1)},${baseline} L${first.x.toFixed(1)},${baseline} Z`;
  }

  excludedTrendGridlines(periods: BatchHealthTrend[]): { y: number; value: number }[] {
    const maximum = this.excludedTrendMaximum(periods);
    if (maximum === 0) {
      return [{ y: this.trendPointY(0, 0), value: 0 }];
    }
    return [0, 0.5, 1].map(fraction => ({
      y: this.trendPointY(maximum * fraction, maximum),
      value: Math.round(maximum * fraction)
    }));
  }

  openExcludedTrendPoint(period: BatchHealthTrend): void {
    if (period.totalExcludedTransactions === 0) {
      return;
    }
    this.openPeriodTransactionExplorer(period, 'EXCLUDED');
  }

  excludedHeatmapCellColor(excludedCount: number, totalTransactions: number): string {
    if (totalTransactions === 0) {
      return '#efeee9';
    }
    if (excludedCount === 0) {
      return '#f9f5f5';
    }
    const impact = excludedCount / totalTransactions;
    return `rgba(240, 180, 0, ${0.18 + Math.min(impact * 6, 1) * 0.62})`;
  }

  excludedHeatmapTextColor(excludedCount: number, totalTransactions: number): string {
    const impact = totalTransactions === 0 ? 0 : excludedCount / totalTransactions;
    return Math.min(impact * 6, 1) >= 0.7 ? '#3a2e00' : excludedCount === 0 ? '#aaa9a1' : '#6b5400';
  }

  excludedTransactionsCellTitle(period: BatchHealthTrend): string {
    const total = period.totalReportedTransactions + period.totalExcludedTransactions;
    const rate = total === 0 ? 0 : (period.totalExcludedTransactions * 100) / total;
    return `Excluded transactions: ${period.totalExcludedTransactions} of ${total} (${rate.toFixed(1)}%) from ${period.periodStart} to ${period.periodEnd}`;
  }

  private loadDashboardDetails(): void {
    if (this.countryRequired()) {
      // No country picked -- don't even make the request. There's nothing wrong to report (this
      // isn't dashboardError, which reads as a failure), and skipping the call entirely avoids
      // paying for a query whose result would just get thrown away unrendered.
      this.dashboardDetails.set(null);
      this.dashboardLoading.set(false);
      this.dashboardError.set(null);
      return;
    }

    const period = this.resolvePeriod();
    if (!period) {
      this.dashboardDetails.set(null);
      this.dashboardError.set('Select both custom dates.');
      return;
    }

    this.dashboardLoading.set(true);
    this.dashboardError.set(null);
    let params = new HttpParams().set('fromDate', period.fromDate).set('toDate', period.toDate).set('country', this.country());
    if (this.reportGroupId() !== 'ALL') {
      params = params.set('reportGroupId', this.reportGroupId());
    }

    this.http.get<TransactionDashboardResponse>('/dashboardDetails/transaction-view', { params }).subscribe({
      next: details => {
        this.dashboardDetails.set({
          ...details,
          batchHealthTrend: details.batchHealthTrend.map(period => ({
            ...period,
            totalReportedTransactions: period.totalReportedTransactions ?? 0,
            totalExcludedTransactions: period.totalExcludedTransactions ?? 0
          }))
        });
        this.dashboardLoading.set(false);
      },
      error: () => {
        this.dashboardDetails.set(null);
        this.dashboardLoading.set(false);
        this.dashboardError.set('Dashboard data could not be loaded.');
      }
    });
  }

  private resolvePeriod(): { fromDate: string; toDate: string } | null {
    if (this.reportPeriod() === 'CUSTOM') {
      if (!this.startDate() || !this.endDate()) {
        return null;
      }
      return { fromDate: this.startDate(), toDate: this.endDate() };
    }

    const toDate = new Date();
    const fromDate = new Date(toDate);
    if (this.reportPeriod() === 'LAST_7_DAYS') {
      fromDate.setDate(fromDate.getDate() - 6);
    } else if (this.reportPeriod() === 'LAST_30_DAYS') {
      fromDate.setDate(fromDate.getDate() - 29);
    }

    return { fromDate: this.toLocalDate(fromDate), toDate: this.toLocalDate(toDate) };
  }

  /** Shares DashboardFilterStateService with Batch View -- filters set on one page are reflected
   *  when you switch to the other tab, which is a deliberate, useful side effect of relocating
   *  this view rather than something to avoid. */
  private restoreRouteFilters(): void {
    const params = this.route.snapshot.queryParamMap;
    const remembered = this.filterState.get();

    this.country.set(params.get('country') ?? remembered?.country ?? 'ALL');
    this.reportGroupId.set(params.get('reportGroupId') ?? remembered?.reportGroupId ?? 'ALL');

    const fromDate = params.get('fromDate');
    const toDate = params.get('toDate');
    if (fromDate && toDate) {
      const periodParam = params.get('period') as ReportPeriod | null;
      this.reportPeriod.set(periodParam ?? 'CUSTOM');
      this.startDate.set(fromDate);
      this.endDate.set(toDate);
      this.filtersApplied.set(true);
    } else if (remembered) {
      this.reportPeriod.set(remembered.reportPeriod);
      this.startDate.set(remembered.startDate);
      this.endDate.set(remembered.endDate);
      this.filtersApplied.set(true);
    }
  }

  private persistRouteFilters(): void {
    const period = this.resolvePeriod();
    // Batch ID isn't a filter on this page (see countryRequired's comment -- this view is
    // country-scoped, not batch-scoped), but the shared DashboardFilterStateService's shape still
    // requires it for Batch View's sake, so it's carried through unchanged rather than reset.
    this.filterState.set({
      batchId: this.filterState.get()?.batchId ?? '',
      country: this.country(),
      reportGroupId: this.reportGroupId(),
      reportPeriod: this.reportPeriod(),
      startDate: period?.fromDate ?? '',
      endDate: period?.toDate ?? ''
    });
    void this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl: true,
      queryParams: {
        fromDate: period?.fromDate ?? null,
        toDate: period?.toDate ?? null,
        period: this.reportPeriod(),
        country: this.country(),
        reportGroupId: this.reportGroupId() === 'ALL' ? null : this.reportGroupId()
      }
    });
  }

  private selectedReportGroupIdOrNull(): number | null {
    return this.reportGroupId() === 'ALL' ? null : Number(this.reportGroupId());
  }

  private toLocalDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }
}
