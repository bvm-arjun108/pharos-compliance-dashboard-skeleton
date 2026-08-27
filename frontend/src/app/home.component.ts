import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

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
}

interface ReportConfigListItem {
  reportGroupId: number;
  reportGroupName: string | null;
}

interface ReportConfigExplorerResponse {
  configurations: ReportConfigListItem[];
}

interface DashboardDetailsResponse {
  batchesRan: number;
  successfulBatches: number;
  batchesNotYetReported: number;
  batchesNeedingAttention: number;
  transformationFailureBatches: number;
  missingAttemptBatches: number;
  activityMissingBatches: number;
  duplicateTransactionBatches: number;
  exclusionBatches: number;
  simulatedTransactionBatches: number;
  softDedupBatches: number;
  totalReportedTransactions: number;
  totalExcludedTransactions: number;
  trendGranularity: TrendGranularity;
  batchHealthTrend: BatchHealthTrend[];
  reportGroupsRequiringAttention: ReportGroupAttention[];
  fromDate: string;
  toDate: string;
}

type TrendGranularity = 'DAILY' | 'WEEKLY' | 'MONTHLY';

interface BatchHealthTrend {
  periodStart: string;
  periodEnd: string;
  batchesRan: number;
  successfulBatches: number;
  batchesNeedingAttention: number;
  transformationFailureBatches: number;
  missingAttemptBatches: number;
  activityMissingBatches: number;
  attentionRate: number;
  totalReportedTransactions: number;
  totalExcludedTransactions: number;
}

interface ReportGroupAttention {
  reportGroupId: number;
  reportGroupName: string | null;
  batchesRan: number;
  successfulBatches: number;
  batchesNeedingAttention: number;
  transformationFailureBatches: number;
  missingAttemptBatches: number;
  activityMissingBatches: number;
  totalReportedTransactions: number;
  totalExcludedTransactions: number;
}

type AttentionSortColumn =
  | 'name'
  | 'batchesRan'
  | 'successfulBatches'
  | 'batchesNeedingAttention'
  | 'activityMissingBatches'
  | 'missingAttemptBatches'
  | 'transformationFailureBatches'
  | 'totalReportedTransactions'
  | 'totalExcludedTransactions';
type AttentionSortDirection = 'asc' | 'desc';

type ReportPeriod = 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'CUSTOM';
type ExplorerStatus = 'ALL' | 'SUCCESSFUL' | 'ATTENTION';
type ExplorerIssueType =
  | 'ALL'
  | 'ACTIVITY_MISSING'
  | 'MISSING_ATTEMPTS'
  | 'TRANSFORMATION'
  | 'DUPLICATE_TRANSFORMATION'
  | 'EXCLUSION'
  | 'SIMULATED'
  | 'SOFT_DEDUP';
type ExplorerMetricFocus = 'DEFAULT' | 'REPORTED' | 'EXCLUDED';

@Component({
  standalone: true,
  imports: [DatePipe, DecimalPipe, FormsModule],
  template: `
    <section class="filter-panel" aria-labelledby="filter-heading">
      <div class="filter-panel__heading">
        <div>
          <p class="eyebrow">Search & filter</p>
          <h2 id="filter-heading">Dashboard criteria</h2>
        </div>
        <div class="filter-panel__actions">
          <button class="text-button" type="button" (click)="resetFilters()">Reset all</button>
        </div>
      </div>

      <form class="filter-form" (submit)="applyFilters($event)">
        <label class="field field--search">
          <span>Batch ID</span>
          <div class="input-shell">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m21 21-4.35-4.35m2.35-5.15a7.5 7.5 0 1 1-15 0 7.5 7.5 0 0 1 15 0Z" /></svg>
            <input type="search" placeholder="Search by batch ID" autocomplete="off" [value]="batchId()" (input)="setBatchId($event)" />
          </div>
        </label>

        <label class="field">
          <span>Country</span>
          @if (countryOptions().length > 0) {
            <select [ngModel]="country()" (ngModelChange)="setCountryValue($event)" [ngModelOptions]="{standalone: true}">
              <option value="ALL">All countries</option>
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
              @for (option of reportGroupOptions(); track option.reportGroupId) {
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

      @if (filtersApplied()) {
        <p class="filter-feedback" role="status">Filters applied to the dashboard.</p>
      }
    </section>

    <section class="kpi-section" aria-labelledby="kpi-heading" [attr.aria-busy]="dashboardLoading()">
      <div class="kpi-section__heading">
        <div>
          <p class="eyebrow">Operational snapshot</p>
          <h2 id="kpi-heading">Overview</h2>
        </div>
        @if (dashboardDetails(); as details) {
          <span>{{ details.fromDate | date:'MMM d' }} – {{ details.toDate | date:'MMM d, y' }}</span>
        }
      </div>

      <div class="kpi-grid">
        <div class="kpi-batch-group">
        <p class="eyebrow kpi-batch-group__label">Batches Overview</p>
        <div class="kpi-batch-group__cards">
        <button class="kpi-card kpi-card--total kpi-card--link" type="button" (click)="openBatchExplorer('ALL')" [disabled]="dashboardLoading() || !!dashboardError()">
          <div class="kpi-card__topline">
            <span>Batches Ran</span>
            <span class="kpi-card__icon" aria-hidden="true">BR</span>
          </div>
          @if (dashboardLoading()) {
            <span class="kpi-loading">Loading…</span>
          } @else if (dashboardError()) {
            <strong class="kpi-error">Unavailable</strong>
            <small>{{ dashboardError() }}</small>
          } @else if (dashboardDetails(); as details) {
            <strong>{{ details.batchesRan | number:'1.0-0' }}</strong>
            <small>Distinct batches selected or completed in the selected period</small>
          }
        </button>

        <article class="kpi-card kpi-card--success">
          <div class="kpi-card__topline">
            <span>Batches Not Needing Attention</span>
            <span class="kpi-card__icon" aria-hidden="true">✓</span>
          </div>
          @if (dashboardLoading()) {
            <span class="kpi-loading">Loading…</span>
          } @else if (dashboardError()) {
            <strong class="kpi-error">Unavailable</strong>
            <small>{{ dashboardError() }}</small>
          } @else if (dashboardDetails(); as details) {
            <button class="attention-summary-link" type="button" (click)="openBatchExplorer('SUCCESSFUL')">
              <strong>{{ details.successfulBatches | number:'1.0-0' }}</strong>
              <small>Distinct batches completed without a detected attention condition</small>
            </button>

            <div class="issue-breakdown" aria-label="Not needing attention breakdown">
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('SUCCESSFUL', 'DUPLICATE_TRANSFORMATION')">
                <span>Duplicate Transactions</span>
                <strong>{{ details.duplicateTransactionBatches | number:'1.0-0' }}</strong>
              </button>
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('SUCCESSFUL', 'EXCLUSION')">
                <span>Exclusion Reason</span>
                <strong>{{ details.exclusionBatches | number:'1.0-0' }}</strong>
              </button>
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('SUCCESSFUL', 'SIMULATED')">
                <span>SML / Simulated</span>
                <strong>{{ details.simulatedTransactionBatches | number:'1.0-0' }}</strong>
              </button>
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('SUCCESSFUL', 'SOFT_DEDUP')">
                <span>Soft Dedup Dropped</span>
                <strong>{{ details.softDedupBatches | number:'1.0-0' }}</strong>
              </button>
            </div>

            <div class="issue-overlap-note">
              @if (notNeedingAttentionBreakdownSum(details) > details.successfulBatches) {
                <span class="issue-overlap-note__badge">{{ notNeedingAttentionBreakdownSum(details) | number:'1.0-0' }}</span>
                <span>condition occurrences across only <strong>{{ details.successfulBatches | number:'1.0-0' }}</strong> distinct batches — a batch can have more than one condition, so the categories above don't sum to the total.</span>
              } @else {
                <span class="issue-overlap-note__badge issue-overlap-note__badge--neutral">✓</span>
                <span>No batch in this period shows more than one of these conditions.</span>
              }
            </div>
          }
        </article>

        <article class="kpi-card kpi-card--attention">
          <div class="kpi-card__topline">
            <span>Batches Needing Attention</span>
            <span class="kpi-card__icon" aria-hidden="true">!</span>
          </div>
          @if (dashboardLoading()) {
            <span class="kpi-loading">Loading…</span>
          } @else if (dashboardError()) {
            <strong class="kpi-error">Unavailable</strong>
            <small>{{ dashboardError() }}</small>
          } @else if (dashboardDetails(); as details) {
            <button class="attention-summary-link" type="button" (click)="openBatchExplorer('ATTENTION')">
              <strong>{{ details.batchesNeedingAttention | number:'1.0-0' }}</strong>
              <small>Distinct batches with one or more detected issues</small>
            </button>

            <div class="issue-breakdown issue-breakdown--three" aria-label="Attention issue breakdown">
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('ATTENTION', 'ACTIVITY_MISSING')">
                <span>Activity Missing</span>
                <strong>{{ details.activityMissingBatches | number:'1.0-0' }}</strong>
              </button>
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('ATTENTION', 'MISSING_ATTEMPTS')">
                <span>Attempts Missing</span>
                <strong>{{ details.missingAttemptBatches | number:'1.0-0' }}</strong>
              </button>
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('ATTENTION', 'TRANSFORMATION')">
                <span>Skipped During Transformation</span>
                <strong>{{ details.transformationFailureBatches | number:'1.0-0' }}</strong>
              </button>
            </div>

            <div class="issue-overlap-note">
              @if (issueBreakdownSum(details) > details.batchesNeedingAttention) {
                <span class="issue-overlap-note__badge">{{ issueBreakdownSum(details) | number:'1.0-0' }}</span>
                <span>issue occurrences across only <strong>{{ details.batchesNeedingAttention | number:'1.0-0' }}</strong> distinct batches — a batch can have more than one issue type, so the categories above don't sum to the total.</span>
              } @else {
                <span class="issue-overlap-note__badge issue-overlap-note__badge--neutral">✓</span>
                <span>No batch in this period shows more than one issue type.</span>
              }
            </div>
          }
        </article>
        </div>
        </div>

        <div class="kpi-transaction-group">
        <p class="eyebrow kpi-batch-group__label">Transactions Overview</p>
        <article class="kpi-card kpi-card--transactions">
          <div class="kpi-card__topline">
            <span>Transaction Totals</span>
            <span class="kpi-card__icon" aria-hidden="true">TX</span>
          </div>
          @if (dashboardLoading()) {
            <span class="kpi-loading">Loading…</span>
          } @else if (dashboardError()) {
            <strong class="kpi-error">Unavailable</strong>
            <small>{{ dashboardError() }}</small>
          } @else if (dashboardDetails(); as details) {
            <div class="transaction-overview-body">
              <div class="exclusion-gauge">
                <svg class="exclusion-gauge__svg" viewBox="0 0 220 130" aria-hidden="true">
                  <path class="exclusion-gauge__band exclusion-gauge__band--healthy" d="M20,110 A90,90 0 0 1 46.36,46.36" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--low" d="M46.36,46.36 A90,90 0 0 1 110,20" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--elevated" d="M110,20 A90,90 0 0 1 173.64,46.36" />
                  <path class="exclusion-gauge__band exclusion-gauge__band--high" d="M173.64,46.36 A90,90 0 0 1 200,110" />
                  <line
                    class="exclusion-gauge__needle"
                    x1="110" y1="110"
                    [attr.x2]="gaugeNeedleX(exclusionRatePercent(details))"
                    [attr.y2]="gaugeNeedleY(exclusionRatePercent(details))"
                  />
                  <circle class="exclusion-gauge__hub" cx="110" cy="110" r="6" />
                </svg>
                <div class="exclusion-gauge__readout">
                  <strong [style.color]="gaugeZoneColor(exclusionRatePercent(details))">{{ formatExclusionRate(exclusionRatePercent(details)) }}</strong>
                  <span>Exclusion Rate</span>
                </div>
              </div>

              <div class="transaction-overview-side">
                <div class="transaction-totals">
                  <div>
                    <span>Expected</span>
                    <strong>{{ expectedTransactions(details) | number:'1.0-0' }}</strong>
                  </div>
                  <div>
                    <span>Reported</span>
                    <strong>{{ details.totalReportedTransactions | number:'1.0-0' }}</strong>
                  </div>
                </div>

                <button
                  type="button"
                  class="exclusion-alert exclusion-alert--link"
                  [disabled]="details.totalExcludedTransactions === 0"
                  [attr.aria-label]="'View ' + details.totalExcludedTransactions + ' excluded transactions'"
                  (click)="openExcludedTransactionsExplorer()"
                >
                  <svg class="exclusion-alert__icon" viewBox="0 0 40 36" aria-hidden="true" focusable="false">
                    <path class="exclusion-alert__triangle" d="M20 2.5 38 33.5H2Z" />
                    <rect class="exclusion-alert__mark" x="18.25" y="11" width="3.5" height="12.5" rx="1.75" />
                    <circle class="exclusion-alert__mark" cx="20" cy="28" r="2" />
                  </svg>
                  <div>
                    <strong>{{ details.totalExcludedTransactions | number:'1.0-0' }}</strong>
                    <span>Excluded</span>
                  </div>
                </button>
              </div>
            </div>

            <div class="exclusion-gauge__legend">
              <span><i class="exclusion-gauge__legend-dot exclusion-gauge__legend-dot--healthy"></i>0–1% Healthy</span>
              <span><i class="exclusion-gauge__legend-dot exclusion-gauge__legend-dot--low"></i>1–5% Low</span>
              <span><i class="exclusion-gauge__legend-dot exclusion-gauge__legend-dot--elevated"></i>5–10% Elevated</span>
              <span><i class="exclusion-gauge__legend-dot exclusion-gauge__legend-dot--high"></i>10%+ High</span>
            </div>

            <small class="transaction-overview-note">Transformer output and filtration exclusions</small>
          }
        </article>
        </div>
      </div>
    </section>

    <section class="operational-trends-section" aria-labelledby="operational-trends-heading">
      <p class="eyebrow" id="operational-trends-heading">Operational trend</p>

      <div class="daily-health-section" aria-labelledby="daily-health-heading">
      <div class="daily-health-heading">
        <div>
          @if (dashboardDetails(); as details) {
            <h2 id="daily-health-heading">{{ trendTitle(details.trendGranularity) }}</h2>
            <p>{{ trendDescription(details.trendGranularity) }}</p>
          } @else {
            <h2 id="daily-health-heading">Batch Health Trend</h2>
          }
        </div>
        @if (dashboardDetails(); as details) {
          <div class="chart-summary">
            <div class="chart-legend" aria-label="Chart legend">
              <span><i class="legend-swatch legend-swatch--success"></i>Successful</span>
              <span><i class="legend-swatch legend-swatch--attention"></i>Needs attention</span>
            </div>
            <strong>{{ overallAttentionRate(details) | number:'1.0-0' }}% attention rate</strong>
          </div>
        }
      </div>

      <div class="daily-health-card">
        @if (dashboardLoading()) {
          <div class="chart-message">Loading batch health trend…</div>
        } @else if (dashboardError()) {
          <div class="chart-message chart-message--error">Batch health trend is unavailable.</div>
        } @else if (dashboardDetails(); as details) {
          @if (details.batchHealthTrend.length === 0) {
            <div class="chart-message">No batch activity is available for this period.</div>
          } @else {
            <div class="daily-chart-scroll">
              <div class="daily-chart" [style.min-width.px]="trendMinimumWidth(details.batchHealthTrend.length, details.trendGranularity)">
                <div class="chart-scale" aria-hidden="true">
                  <span>{{ trendMaximum(details.batchHealthTrend) }}</span>
                  <span>{{ trendMaximum(details.batchHealthTrend) / 2 | number:'1.0-0' }}</span>
                  <span>0</span>
                </div>
                <div class="daily-chart-plot">
                  <div class="chart-grid-lines" aria-hidden="true"><span></span><span></span><span></span></div>
                  <div class="daily-bars">
                    @for (period of details.batchHealthTrend; track period.periodStart) {
                      <div
                        class="daily-column"
                        [attr.title]="period.periodStart + ' to ' + period.periodEnd + ': ' + period.batchesRan + ' ran, ' + period.successfulBatches + ' successful, ' + period.batchesNeedingAttention + ' needing attention'"
                      >
                        <div class="daily-bar-area">
                          @if (period.batchesRan === 0) {
                            <span class="zero-activity" aria-label="No batch activity"></span>
                          } @else {
                            <div class="daily-stack" [style.height.%]="trendBarHeight(period, details.batchHealthTrend)">
                              <button
                                type="button"
                                class="daily-segment daily-segment--attention"
                                [style.height.%]="trendSegmentHeight(period.batchesNeedingAttention, period.batchesRan)"
                                [disabled]="period.batchesNeedingAttention === 0"
                                [attr.aria-label]="'View ' + period.batchesNeedingAttention + ' batches needing attention on ' + period.periodStart"
                                (click)="openPeriodExplorer(period, 'ATTENTION')"
                              >
                                @if (showTrendSegmentLabel(period.batchesNeedingAttention, details.batchHealthTrend)) {
                                  <b>{{ period.batchesNeedingAttention }}</b>
                                }
                              </button>
                              <button
                                type="button"
                                class="daily-segment daily-segment--success"
                                [style.height.%]="trendSegmentHeight(period.successfulBatches, period.batchesRan)"
                                [disabled]="period.successfulBatches === 0"
                                [attr.aria-label]="'View ' + period.successfulBatches + ' successful batches on ' + period.periodStart"
                                (click)="openPeriodExplorer(period, 'SUCCESSFUL')"
                              >
                                @if (showTrendSegmentLabel(period.successfulBatches, details.batchHealthTrend)) {
                                  <b>{{ period.successfulBatches }}</b>
                                }
                              </button>
                            </div>
                          }
                        </div>
                        <span class="daily-split-counts">
                          <button
                            type="button"
                            class="daily-success-count"
                            [disabled]="period.successfulBatches === 0"
                            [attr.aria-label]="'View ' + period.successfulBatches + ' successful batches on ' + period.periodStart"
                            (click)="openPeriodExplorer(period, 'SUCCESSFUL')"
                          >{{ period.successfulBatches }}</button>
                          <button
                            type="button"
                            class="daily-attention-count"
                            [disabled]="period.batchesNeedingAttention === 0"
                            [attr.aria-label]="'View ' + period.batchesNeedingAttention + ' batches needing attention on ' + period.periodStart"
                            (click)="openPeriodExplorer(period, 'ATTENTION')"
                          >{{ period.batchesNeedingAttention }}</button>
                        </span>
                        <span class="daily-date">{{ period.periodStart | date:trendDateFormat(details.trendGranularity):'UTC' }}</span>
                      </div>
                    }
                  </div>
                </div>
              </div>
            </div>
          }
        }
      </div>
      </div>

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
        @if (dashboardLoading()) {
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
    </section>

    <section class="attention-table-section" aria-labelledby="attention-table-heading">
      <div class="attention-table-heading">
        <div>
          <p class="eyebrow">Prioritized investigation</p>
          <h2 id="attention-table-heading">Report Groups Requiring Attention</h2>
        </div>
        @if (dashboardDetails(); as details) {
          <span>{{ details.reportGroupsRequiringAttention.length }} report groups</span>
        }
      </div>

      <div class="attention-table-shell">
        @if (dashboardLoading()) {
          <div class="table-message">Loading report groups…</div>
        } @else if (dashboardError()) {
          <div class="table-message table-message--error">Report-group details are unavailable.</div>
        } @else if (dashboardDetails(); as details) {
          @if (details.reportGroupsRequiringAttention.length === 0) {
            <div class="table-message">No report groups require attention for this period.</div>
          } @else {
            <div class="attention-table-scroll">
              <table class="attention-table">
                <thead>
                  <tr class="column-groups">
                    <th scope="col" rowspan="2" [attr.aria-sort]="attentionAriaSort('name')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('name')">Priority & Report Group{{ attentionSortIndicator('name') }}</button>
                    </th>
                    <th scope="colgroup" colspan="3">Batch Status</th>
                    <th scope="colgroup" colspan="3">Issue Breakdown</th>
                    <th scope="colgroup" colspan="2">Transaction Totals</th>
                  </tr>
                  <tr class="column-labels">
                    <th scope="col" class="number-cell" [attr.aria-sort]="attentionAriaSort('batchesRan')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('batchesRan')">Ran{{ attentionSortIndicator('batchesRan') }}</button>
                    </th>
                    <th scope="col" class="number-cell" [attr.aria-sort]="attentionAriaSort('successfulBatches')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('successfulBatches')">Successful{{ attentionSortIndicator('successfulBatches') }}</button>
                    </th>
                    <th scope="col" class="number-cell" [attr.aria-sort]="attentionAriaSort('batchesNeedingAttention')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('batchesNeedingAttention')">Attention{{ attentionSortIndicator('batchesNeedingAttention') }}</button>
                    </th>
                    <th scope="col" class="number-cell" [attr.aria-sort]="attentionAriaSort('activityMissingBatches')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('activityMissingBatches')">Activity Missing{{ attentionSortIndicator('activityMissingBatches') }}</button>
                    </th>
                    <th scope="col" class="number-cell" [attr.aria-sort]="attentionAriaSort('missingAttemptBatches')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('missingAttemptBatches')">Missing Attempts{{ attentionSortIndicator('missingAttemptBatches') }}</button>
                    </th>
                    <th scope="col" class="number-cell" [attr.aria-sort]="attentionAriaSort('transformationFailureBatches')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('transformationFailureBatches')">Transformation{{ attentionSortIndicator('transformationFailureBatches') }}</button>
                    </th>
                    <th scope="col" class="number-cell" [attr.aria-sort]="attentionAriaSort('totalReportedTransactions')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('totalReportedTransactions')">Reported{{ attentionSortIndicator('totalReportedTransactions') }}</button>
                    </th>
                    <th scope="col" class="number-cell" [attr.aria-sort]="attentionAriaSort('totalExcludedTransactions')">
                      <button type="button" class="sort-header" (click)="setAttentionSort('totalExcludedTransactions')">Excluded{{ attentionSortIndicator('totalExcludedTransactions') }}</button>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  @for (group of sortedReportGroups(details.reportGroupsRequiringAttention); track group.reportGroupId; let rank = $index) {
                    <tr>
                      <th scope="row">
                        <button
                          type="button"
                          class="report-group-cell report-group-link"
                          (click)="openReportGroupExplorer(group)"
                          [attr.aria-label]="'View all batches for ' + (group.reportGroupName || 'report group ' + group.reportGroupId)"
                        >
                          <span class="rank-badge">{{ rank + 1 }}</span>
                          <span>
                            <strong>{{ group.reportGroupName || 'Report Group' }}</strong>
                            <small>ID {{ group.reportGroupId }}</small>
                          </span>
                          <span class="report-group-link__arrow" aria-hidden="true">→</span>
                        </button>
                      </th>
                      <td class="number-cell">
                        <button type="button" class="table-metric-link metric-value" (click)="openReportGroupExplorer(group)" [attr.aria-label]="'View all ' + group.batchesRan + ' batches for ' + (group.reportGroupName || group.reportGroupId)">
                          {{ group.batchesRan | number:'1.0-0' }}
                        </button>
                      </td>
                      <td class="number-cell">
                        @if (group.successfulBatches > 0) {
                          <button type="button" class="table-metric-link metric-pill metric-pill--success" (click)="openReportGroupExplorer(group, 'SUCCESSFUL')" [attr.aria-label]="'View ' + group.successfulBatches + ' successful batches'">{{ group.successfulBatches | number:'1.0-0' }}</button>
                        } @else {
                          <span class="metric-pill metric-pill--success metric-pill--zero">0</span>
                        }
                      </td>
                      <td class="number-cell">
                        @if (group.batchesNeedingAttention > 0) {
                          <button type="button" class="table-metric-link attention-value" (click)="openReportGroupExplorer(group, 'ATTENTION')" [attr.aria-label]="'View ' + group.batchesNeedingAttention + ' batches needing attention'">{{ group.batchesNeedingAttention | number:'1.0-0' }}</button>
                        } @else {
                          <span class="attention-value metric-pill--zero">0</span>
                        }
                      </td>
                      <td class="number-cell">
                        @if (group.activityMissingBatches > 0) {
                          <button type="button" class="table-metric-link metric-pill" (click)="openReportGroupExplorer(group, 'ATTENTION', 'ACTIVITY_MISSING')" [attr.aria-label]="'View ' + group.activityMissingBatches + ' batches with activity missing'">{{ group.activityMissingBatches | number:'1.0-0' }}</button>
                        } @else { <span class="metric-pill metric-pill--zero">0</span> }
                      </td>
                      <td class="number-cell">
                        @if (group.missingAttemptBatches > 0) {
                          <button type="button" class="table-metric-link metric-pill" (click)="openReportGroupExplorer(group, 'ATTENTION', 'MISSING_ATTEMPTS')" [attr.aria-label]="'View ' + group.missingAttemptBatches + ' batches with missing attempts'">{{ group.missingAttemptBatches | number:'1.0-0' }}</button>
                        } @else { <span class="metric-pill metric-pill--zero">0</span> }
                      </td>
                      <td class="number-cell">
                        @if (group.transformationFailureBatches > 0) {
                          <button type="button" class="table-metric-link metric-pill" (click)="openReportGroupExplorer(group, 'ATTENTION', 'TRANSFORMATION')" [attr.aria-label]="'View ' + group.transformationFailureBatches + ' batches with transformation failures'">{{ group.transformationFailureBatches | number:'1.0-0' }}</button>
                        } @else { <span class="metric-pill metric-pill--zero">0</span> }
                      </td>
                      <td class="number-cell transaction-value">
                        @if (group.totalReportedTransactions > 0) {
                          <span class="transaction-static">{{ group.totalReportedTransactions | number:'1.0-0' }}</span>
                        } @else { <span class="transaction-zero">0</span> }
                      </td>
                      <td class="number-cell transaction-value">
                        @if (group.totalExcludedTransactions > 0) {
                          <button type="button" class="table-metric-link transaction-link" (click)="openReportGroupExcludedTransactions(group, $event)" [attr.aria-label]="'View ' + group.totalExcludedTransactions + ' excluded transactions for ' + (group.reportGroupName || group.reportGroupId)">{{ group.totalExcludedTransactions | number:'1.0-0' }}</button>
                        } @else { <span class="transaction-zero">0</span> }
                      </td>
                    </tr>
                  }
                </tbody>
              </table>
            </div>
          }
        }
      </div>
      <p class="table-footnote">Reported transactions represent transformer output, not final downstream reporting confirmation.</p>
    </section>
  `
})
export class HomeComponent implements OnInit {
  readonly batchId = signal('');
  readonly country = signal('ALL');
  readonly reportPeriod = signal<ReportPeriod>('LAST_7_DAYS');
  readonly startDate = signal('');
  readonly endDate = signal('');
  readonly filtersApplied = signal(false);
  readonly dashboardDetails = signal<DashboardDetailsResponse | null>(null);
  readonly dashboardLoading = signal(false);
  readonly dashboardError = signal<string | null>(null);
  readonly countryOptions = signal<CountryOption[]>([]);
  readonly reportGroupId = signal('ALL');
  readonly reportGroupOptions = signal<ReportGroupOption[]>([]);
  readonly attentionSortColumn = signal<AttentionSortColumn>('batchesNeedingAttention');
  readonly attentionSortDirection = signal<AttentionSortDirection>('desc');

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
            .map(config => ({ reportGroupId: config.reportGroupId, reportGroupName: config.reportGroupName }))
            .sort((a, b) => (a.reportGroupName || '').localeCompare(b.reportGroupName || ''))
        ),
      error: () => this.reportGroupOptions.set([])
    });
    this.loadDashboardDetails();
  }

  setBatchId(event: Event): void {
    this.batchId.set((event.target as HTMLInputElement).value);
    this.filtersApplied.set(false);
  }

  setCountryValue(country: string): void {
    this.country.set(country);
    this.filtersApplied.set(false);
  }

  setReportGroupValue(reportGroupId: string): void {
    this.reportGroupId.set(reportGroupId);
    this.filtersApplied.set(false);
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
    this.batchId.set('');
    this.country.set('ALL');
    this.reportGroupId.set('ALL');
    this.reportPeriod.set('LAST_7_DAYS');
    this.startDate.set('');
    this.endDate.set('');
    this.filtersApplied.set(false);
    this.persistRouteFilters();
    this.loadDashboardDetails();
  }

  openBatchExplorer(status: ExplorerStatus, issueType: ExplorerIssueType = 'ALL'): void {
    this.navigateToBatchExplorer(status, issueType, this.selectedReportGroupIdOrNull(), 'DEFAULT');
  }

  // This KPI's "excluded" total is a sum across every batch matching the current filters, not
  // one batch's evidence — so it can't be pinned to a single batch's transaction report (an
  // earlier version tried picking "the batch with the most exclusions" and jump to it, but that
  // showed only that one batch's aggregate, silently wrong whenever the total spanned others).
  // The transactions page instead has a period-wide mode: given a date range (and optional
  // report group/country) with no batchId, it lists every excluded-transaction record across the
  // whole matching batch set directly, via /api/v1/transactions/period-report.
  openExcludedTransactionsExplorer(): void {
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
        reportGroupId: this.selectedReportGroupIdOrNull()
      }
    });
  }

  openReportGroupExplorer(
    group: ReportGroupAttention,
    status: ExplorerStatus = 'ALL',
    issueType: ExplorerIssueType = 'ALL',
    metricFocus: ExplorerMetricFocus = 'DEFAULT'
  ): void {
    this.navigateToBatchExplorer(status, issueType, group.reportGroupId, metricFocus);
  }

  /** Same period-wide transactions view as the "Excluded" KPI card, but scoped to one report
   *  group's total instead of the whole filtered dashboard — the group's excluded count can span
   *  multiple batches, so this can't point at a single batch's transaction report either. */
  openReportGroupExcludedTransactions(group: ReportGroupAttention, event: Event): void {
    event.stopPropagation();
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
        reportGroupId: group.reportGroupId
      }
    });
  }

  /** Clicking the same header again flips direction; clicking a new one sorts by it — descending
   *  for every numeric column (biggest issue/volume first, matching the table's investigative
   *  purpose), ascending for the name column (alphabetical). */
  setAttentionSort(column: AttentionSortColumn): void {
    if (this.attentionSortColumn() === column) {
      this.attentionSortDirection.set(this.attentionSortDirection() === 'desc' ? 'asc' : 'desc');
    } else {
      this.attentionSortColumn.set(column);
      this.attentionSortDirection.set(column === 'name' ? 'asc' : 'desc');
    }
  }

  sortedReportGroups(groups: ReportGroupAttention[]): ReportGroupAttention[] {
    const column = this.attentionSortColumn();
    const direction = this.attentionSortDirection() === 'asc' ? 1 : -1;
    return [...groups].sort((a, b) => {
      if (column === 'name') {
        const nameA = a.reportGroupName || `Report Group ${a.reportGroupId}`;
        const nameB = b.reportGroupName || `Report Group ${b.reportGroupId}`;
        return nameA.localeCompare(nameB) * direction;
      }
      return (a[column] - b[column]) * direction;
    });
  }

  attentionSortIndicator(column: AttentionSortColumn): string {
    if (this.attentionSortColumn() !== column) {
      return '';
    }
    return this.attentionSortDirection() === 'desc' ? ' ↓' : ' ↑';
  }

  attentionAriaSort(column: AttentionSortColumn): 'ascending' | 'descending' | 'none' {
    if (this.attentionSortColumn() !== column) {
      return 'none';
    }
    return this.attentionSortDirection() === 'desc' ? 'descending' : 'ascending';
  }

  openPeriodExplorer(
    period: BatchHealthTrend,
    status: ExplorerStatus = 'ALL',
    issueType: ExplorerIssueType = 'ALL'
  ): void {
    void this.router.navigate(['/batches/explorer'], {
      queryParams: {
        fromDate: period.periodStart,
        toDate: period.periodEnd,
        batchId: this.batchId().trim() || null,
        country: this.country(),
        status,
        issueType,
        reportGroupId: this.selectedReportGroupIdOrNull(),
        metricFocus: 'DEFAULT'
      }
    });
  }

  private navigateToBatchExplorer(
    status: ExplorerStatus,
    issueType: ExplorerIssueType,
    reportGroupId: number | null,
    metricFocus: ExplorerMetricFocus
  ): void {
    const period = this.resolvePeriod();
    if (!period) {
      this.dashboardError.set('Select both custom dates.');
      return;
    }
    void this.router.navigate(['/batches/explorer'], {
      queryParams: {
        fromDate: period.fromDate,
        toDate: period.toDate,
        batchId: this.batchId().trim() || null,
        country: this.country(),
        status,
        issueType,
        reportGroupId,
        metricFocus
      }
    });
  }

  overallAttentionRate(details: DashboardDetailsResponse): number {
    return details.batchesRan === 0 ? 0 : (details.batchesNeedingAttention * 100) / details.batchesRan;
  }

  exclusionRatePercent(details: DashboardDetailsResponse): number {
    const total = details.totalReportedTransactions + details.totalExcludedTransactions;
    return total === 0 ? 0 : (details.totalExcludedTransactions / total) * 100;
  }

  expectedTransactions(details: DashboardDetailsResponse): number {
    return details.totalReportedTransactions + details.totalExcludedTransactions;
  }

  // Maps a rate onto a semicircle gauge where each threshold zone (0-1%, 1-5%, 5-10%, 10%+)
  // gets an equal 45-degree slice, regardless of its numeric width. Real exclusion rates in
  // this app sit well under 1%, so a plain linear 0-100% gauge would always pin the needle at
  // the far left; zone-equal slices keep the needle legible within the "Healthy" range.
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

  formatExclusionRate(ratePercent: number): string {
    return `${ratePercent.toFixed(2)}%`;
  }

  openPeriodTransactionExplorer(period: BatchHealthTrend, metricFocus: ExplorerMetricFocus): void {
    void this.router.navigate(['/batches/explorer'], {
      queryParams: {
        fromDate: period.periodStart,
        toDate: period.periodEnd,
        batchId: this.batchId().trim() || null,
        country: this.country(),
        status: 'ALL',
        issueType: 'ALL',
        reportGroupId: this.selectedReportGroupIdOrNull(),
        metricFocus
      }
    });
  }

  trendReportedTransactionsMaximum(periods: BatchHealthTrend[]): number {
    return Math.max(1, ...periods.map(period => period.totalReportedTransactions));
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

  issueBreakdownSum(details: DashboardDetailsResponse): number {
    return (
      details.transformationFailureBatches +
      details.missingAttemptBatches +
      details.activityMissingBatches
    );
  }

  notNeedingAttentionBreakdownSum(details: DashboardDetailsResponse): number {
    return (
      details.duplicateTransactionBatches +
      details.exclusionBatches +
      details.simulatedTransactionBatches +
      details.softDedupBatches
    );
  }

  trendTitle(granularity: TrendGranularity): string {
    return `${granularity.charAt(0)}${granularity.slice(1).toLowerCase()} Batch Health`;
  }

  trendDescription(granularity: TrendGranularity): string {
    const bucket = granularity.toLowerCase();
    return `Completed batches grouped ${bucket} and split into successful and attention outcomes.`;
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

  trendMaximum(periods: BatchHealthTrend[]): number {
    return Math.max(1, ...periods.map(period => period.batchesRan));
  }

  trendBarHeight(period: BatchHealthTrend, periods: BatchHealthTrend[]): number {
    return (period.batchesRan / this.trendMaximum(periods)) * 100;
  }

  trendSegmentHeight(value: number, total: number): number {
    return total === 0 ? 0 : (value / total) * 100;
  }

  showTrendSegmentLabel(value: number, periods: BatchHealthTrend[]): boolean {
    return value > 0 && (value / this.trendMaximum(periods)) * 181 >= 20;
  }

  trendMinimumWidth(periodCount: number, granularity: TrendGranularity): number {
    const bucketWidth = granularity === 'DAILY' ? 42 : granularity === 'WEEKLY' ? 66 : 82;
    return Math.max(720, periodCount * bucketWidth);
  }

  heatmapMinimumWidth(periodCount: number, granularity: TrendGranularity): number {
    const bucketWidth = granularity === 'DAILY' ? 42 : granularity === 'WEEKLY' ? 64 : 78;
    return Math.max(760, 180 + periodCount * bucketWidth);
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
    const period = this.resolvePeriod();
    if (!period) {
      this.dashboardDetails.set(null);
      this.dashboardError.set('Select both custom dates.');
      return;
    }

    this.dashboardLoading.set(true);
    this.dashboardError.set(null);
    let params = new HttpParams()
      .set('fromDate', period.fromDate)
      .set('toDate', period.toDate)
      .set('batchId', this.batchId().trim())
      .set('country', this.country());
    if (this.reportGroupId() !== 'ALL') {
      params = params.set('reportGroupId', this.reportGroupId());
    }

    this.http.get<DashboardDetailsResponse>('/dashboardDetails', { params }).subscribe({
      next: details => {
        this.dashboardDetails.set({
          ...details,
          batchHealthTrend: details.batchHealthTrend.map(period => ({
            ...period,
            transformationFailureBatches: period.transformationFailureBatches ?? 0,
            missingAttemptBatches: period.missingAttemptBatches ?? 0,
            activityMissingBatches: period.activityMissingBatches ?? 0,
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

  private restoreRouteFilters(): void {
    const params = this.route.snapshot.queryParamMap;
    this.batchId.set(params.get('batchId') ?? '');
    this.country.set(params.get('country') ?? 'ALL');
    this.reportGroupId.set(params.get('reportGroupId') ?? 'ALL');
    const fromDate = params.get('fromDate');
    const toDate = params.get('toDate');
    if (fromDate && toDate) {
      this.reportPeriod.set('CUSTOM');
      this.startDate.set(fromDate);
      this.endDate.set(toDate);
      this.filtersApplied.set(true);
    }
  }

  private persistRouteFilters(): void {
    const period = this.resolvePeriod();
    void this.router.navigate([], {
      relativeTo: this.route,
      replaceUrl: true,
      queryParams: {
        fromDate: period?.fromDate ?? null,
        toDate: period?.toDate ?? null,
        batchId: this.batchId().trim() || null,
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
