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

interface ReportConfigListItem {
  reportGroupId: number;
  reportGroupName: string | null;
  countryCode: string;
}

interface ReportConfigExplorerResponse {
  configurations: ReportConfigListItem[];
}

interface TransactionOverview {
  selected: number;
  expected: number;
  excluded: number;
  notReported: number;
}

type TrendGranularity = 'DAILY' | 'WEEKLY' | 'MONTHLY';

interface BatchHealthTrend {
  periodStart: string;
  periodEnd: string;
  totalReportedTransactions: number;
  totalExcludedTransactions: number;
}

// Only the fields this page actually reads -- /dashboardDetails returns a lot more (batch counts,
// report-groups-requiring-attention, ...) that Batch View owns, but a narrower interface here is
// fine since HttpClient's typed get<T>() doesn't validate the response shape, only casts it.
interface DashboardDetailsResponse {
  transactionOverview: TransactionOverview;
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
          @if (dashboardLoading()) {
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
  `
})
export class TransactionOverviewComponent implements OnInit {
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

  setBatchId(event: Event): void {
    this.batchId.set((event.target as HTMLInputElement).value);
    this.filtersApplied.set(false);
  }

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
        reportGroupId: this.selectedReportGroupIdOrNull(),
        status: 'EXCLUDED',
        view: 'overview'
      }
    });
  }

  openNotReportedTransactionsExplorer(): void {
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
        view: 'overview'
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
        status: metricFocus
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
