import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, OnInit, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';

interface HealthResponse {
  status: string;
  service: string;
  timestamp: string;
}

interface CountryOption {
  code: string;
  name: string;
}

interface BatchFilterOptionsResponse {
  countries: CountryOption[];
}

interface DashboardDetailsResponse {
  batchesRan: number;
  successfulBatches: number;
  batchesNotYetReported: number;
  batchesNeedingAttention: number;
  transformationFailureBatches: number;
  missingAttemptBatches: number;
  filtrationFailureBatches: number;
  reconciliationFailureBatches: number;
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
  filtrationFailureBatches: number;
  reconciliationFailureBatches: number;
  attentionRate: number;
}

interface ReportGroupAttention {
  reportGroupId: number;
  reportGroupName: string | null;
  batchesRan: number;
  successfulBatches: number;
  batchesNeedingAttention: number;
  transformationFailureBatches: number;
  missingAttemptBatches: number;
  filtrationFailureBatches: number;
  reconciliationFailureBatches: number;
  totalReportedTransactions: number;
  totalExcludedTransactions: number;
}

type ReportPeriod = 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS' | 'CUSTOM';
type ExplorerStatus = 'ALL' | 'SUCCESSFUL' | 'ATTENTION' | 'NOT_YET_REPORTED';
type ExplorerIssueType = 'ALL' | 'TRANSFORMATION' | 'MISSING_ATTEMPTS' | 'FILTRATION' | 'RECONCILIATION';
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
          <div class="status" [class.online]="health()?.status === 'UP'" [class.offline]="connectionFailed()">
            <span class="dot"></span>
            @if (health(); as backendHealth) {
              <strong>System {{ backendHealth.status }}</strong>
              <small>{{ backendHealth.timestamp | date:'shortTime' }}</small>
            } @else if (connectionFailed()) {
              <strong>System offline</strong>
            } @else {
              <strong>Checking…</strong>
            }
          </div>
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

        <button class="kpi-card kpi-card--success kpi-card--link" type="button" (click)="openBatchExplorer('SUCCESSFUL')" [disabled]="dashboardLoading() || !!dashboardError()">
          <div class="kpi-card__topline">
            <span>Successful Batches</span>
            <span class="kpi-card__icon" aria-hidden="true">✓</span>
          </div>
          @if (dashboardLoading()) {
            <span class="kpi-loading">Loading…</span>
          } @else if (dashboardError()) {
            <strong class="kpi-error">Unavailable</strong>
            <small>{{ dashboardError() }}</small>
          } @else if (dashboardDetails(); as details) {
            <strong>{{ details.successfulBatches | number:'1.0-0' }}</strong>
            <small>Distinct batches completed without a detected attention condition</small>
          }
        </button>

        <button class="kpi-card kpi-card--pending kpi-card--link" type="button" (click)="openBatchExplorer('NOT_YET_REPORTED')" [disabled]="dashboardLoading() || !!dashboardError()">
          <div class="kpi-card__topline">
            <span>Not Yet Reported</span>
            <span class="kpi-card__icon" aria-hidden="true">…</span>
          </div>
          @if (dashboardLoading()) {
            <span class="kpi-loading">Loading…</span>
          } @else if (dashboardError()) {
            <strong class="kpi-error">Unavailable</strong>
            <small>{{ dashboardError() }}</small>
          } @else if (dashboardDetails(); as details) {
            <strong>{{ details.batchesNotYetReported | number:'1.0-0' }}</strong>
            <small>Selected but not yet transformed — no reconciliation record yet</small>
          }
        </button>

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
            <div class="transaction-totals">
              <div>
                <span>Reported</span>
                <strong>{{ details.totalReportedTransactions | number:'1.0-0' }}</strong>
              </div>
              <div>
                <span>Excluded</span>
                <strong>{{ details.totalExcludedTransactions | number:'1.0-0' }}</strong>
              </div>
            </div>
            <small>Transformer output and filtration exclusions</small>
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

            <div class="issue-breakdown" aria-label="Attention issue breakdown">
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('ATTENTION', 'TRANSFORMATION')">
                <span>Transformation Failure</span>
                <strong>{{ details.transformationFailureBatches | number:'1.0-0' }}</strong>
              </button>
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('ATTENTION', 'MISSING_ATTEMPTS')">
                <span>Missing Transaction Attempts</span>
                <strong>{{ details.missingAttemptBatches | number:'1.0-0' }}</strong>
              </button>
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('ATTENTION', 'FILTRATION')">
                <span>Filtration Failure</span>
                <strong>{{ details.filtrationFailureBatches | number:'1.0-0' }}</strong>
              </button>
              <button class="issue-kpi" type="button" (click)="openBatchExplorer('ATTENTION', 'RECONCILIATION')">
                <span>Reconciliation Failure</span>
                <strong>{{ details.reconciliationFailureBatches | number:'1.0-0' }}</strong>
              </button>
            </div>

            @if (issueBreakdownSum(details) > details.batchesNeedingAttention) {
              <div class="issue-overlap-note">
                <span class="issue-overlap-note__badge">{{ issueBreakdownSum(details) | number:'1.0-0' }}</span>
                <span>issue occurrences across only <strong>{{ details.batchesNeedingAttention | number:'1.0-0' }}</strong> distinct batches — a batch can have more than one issue type, so the categories above don't sum to the total.</span>
              </div>
            }
          }
        </article>
      </div>
    </section>

    <section class="daily-health-section" aria-labelledby="daily-health-heading">
      <div class="daily-health-heading">
        <div>
          <p class="eyebrow">Operational trend</p>
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
                              <span
                                class="daily-segment daily-segment--attention"
                                [style.height.%]="trendSegmentHeight(period.batchesNeedingAttention, period.batchesRan)"
                              >
                                @if (showTrendSegmentLabel(period.batchesNeedingAttention, details.batchHealthTrend)) {
                                  <b>{{ period.batchesNeedingAttention }}</b>
                                }
                              </span>
                              <span
                                class="daily-segment daily-segment--success"
                                [style.height.%]="trendSegmentHeight(period.successfulBatches, period.batchesRan)"
                              >
                                @if (showTrendSegmentLabel(period.successfulBatches, details.batchHealthTrend)) {
                                  <b>{{ period.successfulBatches }}</b>
                                }
                              </span>
                            </div>
                          }
                        </div>
                        <span
                          class="daily-split-counts"
                          [attr.aria-label]="period.successfulBatches + ' successful, ' + period.batchesNeedingAttention + ' needing attention'"
                        >
                          <b class="daily-success-count">{{ period.successfulBatches }}</b>
                          <b class="daily-attention-count">{{ period.batchesNeedingAttention }}</b>
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
    </section>

    <section class="issue-trend-section" aria-labelledby="issue-trend-heading">
      <div class="issue-trend-heading">
        <div>
          <p class="eyebrow">Root-cause signals</p>
          <h2 id="issue-trend-heading">{{ issueTrendTitle(dashboardDetails()?.trendGranularity) }}</h2>
          <p>Cell intensity shows the percentage of batches affected; labels show affected-batch counts.</p>
        </div>
        <div class="heatmap-legend" aria-label="Heatmap intensity legend">
          <span>Lower</span><i></i><span>Higher impact</span>
        </div>
      </div>

      <div class="issue-trend-card">
        @if (dashboardLoading()) {
          <div class="chart-message">Loading issue drivers…</div>
        } @else if (dashboardError()) {
          <div class="chart-message chart-message--error">Issue-driver data is unavailable.</div>
        } @else if (dashboardDetails(); as details) {
          <div class="heatmap-scroll">
            <div
              class="issue-heatmap"
              role="table"
              aria-label="Issue drivers by reporting period"
              [style.min-width.px]="heatmapMinimumWidth(details.batchHealthTrend.length, details.trendGranularity)"
              [style.grid-template-columns]="'180px repeat(' + details.batchHealthTrend.length + ', minmax(38px, 1fr))'"
            >
              <div class="heatmap-corner" role="columnheader">Issue type</div>
              @for (period of details.batchHealthTrend; track period.periodStart) {
                <div class="heatmap-period" role="columnheader" [attr.title]="period.periodStart + ' to ' + period.periodEnd">
                  {{ period.periodStart | date:trendDateFormat(details.trendGranularity):'UTC' }}
                </div>
              }

              <div class="heatmap-row-label" role="rowheader"><span></span>Transformation failure</div>
              @for (period of details.batchHealthTrend; track period.periodStart) {
                <div
                  class="heatmap-cell"
                  role="cell"
                  [style.background]="heatmapCellColor(period.transformationFailureBatches, period.batchesRan)"
                  [style.color]="heatmapTextColor(period.transformationFailureBatches, period.batchesRan)"
                  [attr.title]="issueCellTitle('Transformation failure', period.transformationFailureBatches, period)"
                >{{ period.transformationFailureBatches }}</div>
              }

              <div class="heatmap-row-label" role="rowheader"><span></span>Missing attempts</div>
              @for (period of details.batchHealthTrend; track period.periodStart) {
                <div
                  class="heatmap-cell"
                  role="cell"
                  [style.background]="heatmapCellColor(period.missingAttemptBatches, period.batchesRan)"
                  [style.color]="heatmapTextColor(period.missingAttemptBatches, period.batchesRan)"
                  [attr.title]="issueCellTitle('Missing attempts', period.missingAttemptBatches, period)"
                >{{ period.missingAttemptBatches }}</div>
              }

              <div class="heatmap-row-label" role="rowheader"><span></span>Filtration failure</div>
              @for (period of details.batchHealthTrend; track period.periodStart) {
                <div
                  class="heatmap-cell"
                  role="cell"
                  [style.background]="heatmapCellColor(period.filtrationFailureBatches, period.batchesRan)"
                  [style.color]="heatmapTextColor(period.filtrationFailureBatches, period.batchesRan)"
                  [attr.title]="issueCellTitle('Filtration failure', period.filtrationFailureBatches, period)"
                >{{ period.filtrationFailureBatches }}</div>
              }

              <div class="heatmap-row-label" role="rowheader"><span></span>Reconciliation failure</div>
              @for (period of details.batchHealthTrend; track period.periodStart) {
                <div
                  class="heatmap-cell"
                  role="cell"
                  [style.background]="heatmapCellColor(period.reconciliationFailureBatches, period.batchesRan)"
                  [style.color]="heatmapTextColor(period.reconciliationFailureBatches, period.batchesRan)"
                  [attr.title]="issueCellTitle('Reconciliation failure', period.reconciliationFailureBatches, period)"
                >{{ period.reconciliationFailureBatches }}</div>
              }
            </div>
          </div>
        }
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
                    <th scope="col" rowspan="2">Priority & Report Group</th>
                    <th scope="colgroup" colspan="3">Batch Status</th>
                    <th scope="colgroup" colspan="4">Issue Breakdown</th>
                    <th scope="colgroup" colspan="2">Transaction Totals</th>
                  </tr>
                  <tr class="column-labels">
                    <th scope="col" class="number-cell">Ran</th>
                    <th scope="col" class="number-cell">Successful</th>
                    <th scope="col" class="number-cell" aria-sort="descending">Attention ↓</th>
                    <th scope="col" class="number-cell">Transformation</th>
                    <th scope="col" class="number-cell">Missing Attempts</th>
                    <th scope="col" class="number-cell">Filtration</th>
                    <th scope="col" class="number-cell">Reconciliation</th>
                    <th scope="col" class="number-cell">Reported</th>
                    <th scope="col" class="number-cell">Excluded</th>
                  </tr>
                </thead>
                <tbody>
                  @for (group of details.reportGroupsRequiringAttention; track group.reportGroupId; let rank = $index) {
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
                        @if (group.transformationFailureBatches > 0) {
                          <button type="button" class="table-metric-link metric-pill" (click)="openReportGroupExplorer(group, 'ATTENTION', 'TRANSFORMATION')" [attr.aria-label]="'View ' + group.transformationFailureBatches + ' batches with transformation failures'">{{ group.transformationFailureBatches | number:'1.0-0' }}</button>
                        } @else { <span class="metric-pill metric-pill--zero">0</span> }
                      </td>
                      <td class="number-cell">
                        @if (group.missingAttemptBatches > 0) {
                          <button type="button" class="table-metric-link metric-pill" (click)="openReportGroupExplorer(group, 'ATTENTION', 'MISSING_ATTEMPTS')" [attr.aria-label]="'View ' + group.missingAttemptBatches + ' batches with missing attempts'">{{ group.missingAttemptBatches | number:'1.0-0' }}</button>
                        } @else { <span class="metric-pill metric-pill--zero">0</span> }
                      </td>
                      <td class="number-cell">
                        @if (group.filtrationFailureBatches > 0) {
                          <button type="button" class="table-metric-link metric-pill" (click)="openReportGroupExplorer(group, 'ATTENTION', 'FILTRATION')" [attr.aria-label]="'View ' + group.filtrationFailureBatches + ' batches with filtration errors'">{{ group.filtrationFailureBatches | number:'1.0-0' }}</button>
                        } @else { <span class="metric-pill metric-pill--zero">0</span> }
                      </td>
                      <td class="number-cell">
                        @if (group.reconciliationFailureBatches > 0) {
                          <button type="button" class="table-metric-link metric-pill" (click)="openReportGroupExplorer(group, 'ATTENTION', 'RECONCILIATION')" [attr.aria-label]="'View ' + group.reconciliationFailureBatches + ' batches with reconciliation imbalance'">{{ group.reconciliationFailureBatches | number:'1.0-0' }}</button>
                        } @else { <span class="metric-pill metric-pill--zero">0</span> }
                      </td>
                      <td class="number-cell transaction-value">
                        @if (group.totalReportedTransactions > 0) {
                          <button type="button" class="table-metric-link transaction-link" (click)="openReportGroupExplorer(group, 'ALL', 'ALL', 'REPORTED')" [attr.aria-label]="'View batches contributing ' + group.totalReportedTransactions + ' reported transactions'">{{ group.totalReportedTransactions | number:'1.0-0' }}</button>
                        } @else { <span class="transaction-zero">0</span> }
                      </td>
                      <td class="number-cell transaction-value">
                        @if (group.totalExcludedTransactions > 0) {
                          <button type="button" class="table-metric-link transaction-link" (click)="openReportGroupExplorer(group, 'ALL', 'ALL', 'EXCLUDED')" [attr.aria-label]="'View batches contributing ' + group.totalExcludedTransactions + ' excluded transactions'">{{ group.totalExcludedTransactions | number:'1.0-0' }}</button>
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
  readonly health = signal<HealthResponse | null>(null);
  readonly connectionFailed = signal(false);
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

  constructor(
    private readonly http: HttpClient,
    private readonly router: Router,
    private readonly route: ActivatedRoute
  ) {}

  ngOnInit(): void {
    this.restoreRouteFilters();
    this.http.get<HealthResponse>('/api/v1/health').subscribe({
      next: value => this.health.set(value),
      error: () => this.connectionFailed.set(true)
    });
    this.http.get<BatchFilterOptionsResponse>('/api/v1/batches/filter-options').subscribe({
      next: options => this.countryOptions.set(options.countries),
      error: () => this.countryOptions.set([])
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
    this.reportPeriod.set('LAST_7_DAYS');
    this.startDate.set('');
    this.endDate.set('');
    this.filtersApplied.set(false);
    this.persistRouteFilters();
    this.loadDashboardDetails();
  }

  openBatchExplorer(status: ExplorerStatus, issueType: ExplorerIssueType = 'ALL'): void {
    this.navigateToBatchExplorer(status, issueType, null, 'DEFAULT');
  }

  openReportGroupExplorer(
    group: ReportGroupAttention,
    status: ExplorerStatus = 'ALL',
    issueType: ExplorerIssueType = 'ALL',
    metricFocus: ExplorerMetricFocus = 'DEFAULT'
  ): void {
    this.navigateToBatchExplorer(status, issueType, group.reportGroupId, metricFocus);
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

  issueBreakdownSum(details: DashboardDetailsResponse): number {
    return (
      details.transformationFailureBatches +
      details.missingAttemptBatches +
      details.filtrationFailureBatches +
      details.reconciliationFailureBatches
    );
  }

  trendTitle(granularity: TrendGranularity): string {
    return `${granularity.charAt(0)}${granularity.slice(1).toLowerCase()} Batch Health`;
  }

  trendDescription(granularity: TrendGranularity): string {
    const bucket = granularity.toLowerCase();
    return `Completed batches grouped ${bucket} and split into successful and attention outcomes.`;
  }

  issueTrendTitle(granularity: TrendGranularity | undefined): string {
    if (!granularity) {
      return 'Issue Driver Trend';
    }
    return `${granularity.charAt(0)}${granularity.slice(1).toLowerCase()} Issue Driver Trend`;
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

  heatmapCellColor(issueCount: number, batchesRan: number): string {
    if (batchesRan === 0) {
      return '#efeee9';
    }
    if (issueCount === 0) {
      return '#f9f5f5';
    }
    const impact = issueCount / batchesRan;
    return `rgba(196, 71, 79, ${0.14 + impact * 0.66})`;
  }

  heatmapTextColor(issueCount: number, batchesRan: number): string {
    const impact = batchesRan === 0 ? 0 : issueCount / batchesRan;
    return impact >= 0.7 ? '#ffffff' : issueCount === 0 ? '#aaa9a1' : '#63343a';
  }

  issueCellTitle(label: string, issueCount: number, period: BatchHealthTrend): string {
    const rate = period.batchesRan === 0 ? 0 : (issueCount * 100) / period.batchesRan;
    return `${label}: ${issueCount} affected batches (${rate.toFixed(0)}%) from ${period.periodStart} to ${period.periodEnd}`;
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
    const params = new HttpParams()
      .set('fromDate', period.fromDate)
      .set('toDate', period.toDate)
      .set('batchId', this.batchId().trim())
      .set('country', this.country());

    this.http.get<DashboardDetailsResponse>('/dashboardDetails', { params }).subscribe({
      next: details => {
        this.dashboardDetails.set({
          ...details,
          batchHealthTrend: details.batchHealthTrend.map(period => ({
            ...period,
            transformationFailureBatches: period.transformationFailureBatches ?? 0,
            missingAttemptBatches: period.missingAttemptBatches ?? 0,
            filtrationFailureBatches: period.filtrationFailureBatches ?? 0,
            reconciliationFailureBatches: period.reconciliationFailureBatches ?? 0
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
        country: this.country()
      }
    });
  }

  private toLocalDate(date: Date): string {
    const year = date.getFullYear();
    const month = String(date.getMonth() + 1).padStart(2, '0');
    const day = String(date.getDate()).padStart(2, '0');
    return `${year}-${month}-${day}`;
  }
}
