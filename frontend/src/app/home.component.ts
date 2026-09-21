import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, OnInit, computed, inject, signal } from '@angular/core';
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

// Batch View's own narrower response -- Transactions Overview reads a separate endpoint
// (/dashboardDetails/transaction-view) with its own response shape, since the two pages don't
// share a payload anymore.
interface BatchDashboardResponse {
  batchesRan: number;
  successfulBatches: number;
  batchesNeedingAttention: number;
  transformationFailureBatches: number;
  missingAttemptBatches: number;
  activityMissingBatches: number;
  duplicateTransactionBatches: number;
  exclusionBatches: number;
  simulatedTransactionBatches: number;
  softDedupBatches: number;
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

type ReportPeriod = DashboardReportPeriod;
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
    </section>

    <section class="attention-table-section" aria-labelledby="attention-table-heading">
      <div class="attention-table-heading">
        <div>
          <p class="eyebrow">{{ attentionTableScoped() ? 'Batch health' : 'Prioritized investigation' }}</p>
          <h2 id="attention-table-heading">{{ attentionTableScoped() ? 'Report Group Batch Details' : 'Report Groups Requiring Attention' }}</h2>
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
            <div class="table-message">{{ attentionTableScoped() ? 'No batches ran for this report group in this period.' : 'No report groups require attention for this period.' }}</div>
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
                  @for (group of pagedReportGroups(details.reportGroupsRequiringAttention); track group.reportGroupId; let rank = $index) {
                    <tr>
                      <th scope="row">
                        <button
                          type="button"
                          class="report-group-cell report-group-link"
                          (click)="openReportGroupExplorer(group)"
                          [attr.aria-label]="'View all batches for ' + (group.reportGroupName || 'report group ' + group.reportGroupId)"
                        >
                          <span class="rank-badge">{{ rank + 1 + attentionPage() * attentionPageSize }}</span>
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
            <footer class="attention-pagination">
              <span>{{ attentionPageRange(details.reportGroupsRequiringAttention.length) }}</span>
              <div>
                <button type="button" (click)="previousAttentionPage()" [disabled]="attentionPage() === 0">← Previous</button>
                @for (item of attentionPageNumbers(details.reportGroupsRequiringAttention.length); track $index) {
                  @if (item === '…') {
                    <span class="attention-page-ellipsis">…</span>
                  } @else {
                    <button type="button" class="attention-page-number" [class.is-active]="item === attentionPage() + 1" [attr.aria-current]="item === attentionPage() + 1 ? 'page' : null" (click)="goToAttentionPage(item)">{{ item }}</button>
                  }
                }
                <button type="button" (click)="nextAttentionPage(details.reportGroupsRequiringAttention.length)" [disabled]="(attentionPage() + 1) * attentionPageSize >= details.reportGroupsRequiringAttention.length">Next →</button>
              </div>
            </footer>
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
  readonly dashboardDetails = signal<BatchDashboardResponse | null>(null);
  readonly dashboardLoading = signal(false);
  readonly dashboardError = signal<string | null>(null);
  readonly countryOptions = signal<CountryOption[]>([]);
  readonly reportGroupId = signal('ALL');
  readonly reportGroupOptions = signal<ReportGroupOption[]>([]);
  readonly attentionSortColumn = signal<AttentionSortColumn>('batchesNeedingAttention');
  readonly attentionSortDirection = signal<AttentionSortDirection>('desc');
  readonly attentionPage = signal(0);
  readonly attentionPageSize = 10;

  /** Mirrors the backend's own override in DashboardRepository#getReportGroupsRequiringAttention --
   *  once a country or report group is picked, that's a request to see that scope's full batch
   *  health (attention-needing or not), not a narrower "only the problem ones" slice on top of an
   *  already-narrow selection. Drives this table's title/copy so the UI says what it's actually
   *  showing instead of a label that's only accurate for the unfiltered view. */
  readonly attentionTableScoped = computed(() => this.country() !== 'ALL' || this.reportGroupId() !== 'ALL');

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
    this.http.get<ReportGroupOption[]>('/api/v1/report-configs/report-groups').subscribe({
      next: options => this.reportGroupOptions.set(options),
      error: () => this.reportGroupOptions.set([])
    });
    this.loadDashboardDetails();
  }

  setBatchId(event: Event): void {
    this.batchId.set((event.target as HTMLInputElement).value);
    this.filtersApplied.set(false);
  }

  /** Report groups belonging to the selected country -- every report group belongs to exactly
   *  one country, so this is a genuine many-to-one narrowing (a country can have several report
   *  groups). Unfiltered when country is 'ALL'. Same fix as batch-explorer.component.ts's
   *  filteredReportGroupOptions -- both dropdowns had the identical bug (report group list never
   *  scoped to the selected country) since both load from the same unfiltered /api/v1/report-configs
   *  call. */
  readonly filteredReportGroupOptions = computed(() => {
    const country = this.country();
    return country === 'ALL'
      ? this.reportGroupOptions()
      : this.reportGroupOptions().filter(option => option.countryCode === country);
  });

  setCountryValue(country: string): void {
    this.country.set(country);
    this.filtersApplied.set(false);
    // A report group belongs to exactly one country -- if the newly selected country no longer
    // matches the currently selected report group, that report group is about to disappear from
    // filteredReportGroupOptions above. Clearing it here keeps the two fields from ever
    // contradicting each other.
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
    // The reverse direction: a report group pins down its country unambiguously, so selecting one
    // syncs the country field to match rather than truncating the country dropdown to a single
    // option -- the user can still freely browse other countries afterward (which re-filters the
    // report group list above, including clearing this selection if it's no longer valid there).
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
        reportGroupId: group.reportGroupId,
        // See openExcludedTransactionsExplorer — the period view is not excluded-only, so the
        // status the user clicked has to be carried through explicitly.
        status: 'EXCLUDED',
        // This column is SUM(excluded_txn) over these exact batches, not the Transactions
        // Overview page's "ever excluded across a transaction's whole history" definition —
        // batchScopedExcluded picks the matching simple, batch-scoped evidence query instead of
        // the all-time rollup, so what shows up here actually reconciles with the number clicked.
        batchScopedExcluded: true
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
    this.attentionPage.set(0);
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

  /** Sorted, then sliced to the current page — 10 rows at a time so a long attention list stays
   *  scannable instead of stretching the page indefinitely. */
  pagedReportGroups(groups: ReportGroupAttention[]): ReportGroupAttention[] {
    const sorted = this.sortedReportGroups(groups);
    const start = this.attentionPage() * this.attentionPageSize;
    return sorted.slice(start, start + this.attentionPageSize);
  }

  attentionTotalPages(totalCount: number): number {
    return Math.max(1, Math.ceil(totalCount / this.attentionPageSize));
  }

  attentionPageRange(totalCount: number): string {
    if (totalCount === 0) {
      return '0 of 0';
    }
    const start = this.attentionPage() * this.attentionPageSize + 1;
    const end = Math.min(start + this.attentionPageSize - 1, totalCount);
    return `${start}–${end} of ${totalCount}`;
  }

  attentionPageNumbers(totalCount: number): (number | '…')[] {
    const total = this.attentionTotalPages(totalCount);
    const current = this.attentionPage() + 1;
    const delta = 2;
    const rangeStart = Math.max(2, current - delta);
    const rangeEnd = Math.min(total - 1, current + delta);

    const pages: (number | '…')[] = [1];
    if (rangeStart > 2) {
      pages.push('…');
    }
    for (let i = rangeStart; i <= rangeEnd; i++) {
      pages.push(i);
    }
    if (rangeEnd < total - 1) {
      pages.push('…');
    }
    if (total > 1) {
      pages.push(total);
    }
    return pages;
  }

  previousAttentionPage(): void {
    if (this.attentionPage() > 0) {
      this.attentionPage.set(this.attentionPage() - 1);
    }
  }

  nextAttentionPage(totalCount: number): void {
    if (this.attentionPage() + 1 < this.attentionTotalPages(totalCount)) {
      this.attentionPage.set(this.attentionPage() + 1);
    }
  }

  goToAttentionPage(pageNumber: number): void {
    this.attentionPage.set(pageNumber - 1);
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

  overallAttentionRate(details: BatchDashboardResponse): number {
    return details.batchesRan === 0 ? 0 : (details.batchesNeedingAttention * 100) / details.batchesRan;
  }

  issueBreakdownSum(details: BatchDashboardResponse): number {
    return (
      details.transformationFailureBatches +
      details.missingAttemptBatches +
      details.activityMissingBatches
    );
  }

  notNeedingAttentionBreakdownSum(details: BatchDashboardResponse): number {
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

    this.http.get<BatchDashboardResponse>('/dashboardDetails/batch-view', { params }).subscribe({
      next: details => {
        this.attentionPage.set(0);
        this.dashboardDetails.set(details);
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

  /** Merges the URL's query params with whatever the user last applied this session, per field —
   *  the URL wins when a given param is actually present (a deep link, a bookmark, or another
   *  page's own "back to dashboard" link that only sets some fields, like Batch Explorer's), and
   *  the remembered service state fills in everything the URL doesn't specify. This is what makes
   *  filters survive navigating away and back via a plain nav-bar link, which carries no query
   *  params at all — most of this app's own links back to the dashboard are exactly that. */
  private restoreRouteFilters(): void {
    const params = this.route.snapshot.queryParamMap;
    const remembered = this.filterState.get();

    this.batchId.set(params.get('batchId') ?? remembered?.batchId ?? '');
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
    this.filterState.set({
      batchId: this.batchId().trim(),
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
