import { DatePipe, DecimalPipe } from '@angular/common';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Component, DestroyRef, OnInit, computed, inject, signal } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ActivatedRoute, ParamMap, Router, RouterLink } from '@angular/router';

type TransactionMetric =
  | 'ALL'
  | 'SELECTED'
  | 'ATTEMPTS_FOUND'
  | 'MISSING'
  | 'ACTIVITY_MISSING'
  | 'EXPECTED_ELIGIBLE'
  | 'ACTUAL_ELIGIBLE'
  | 'TRANSFORMED'
  | 'FAILED'
  | 'EXPECTED_REPORTABLE'
  | 'ACTUAL_REPORTABLE'
  | 'EXCLUDED'
  | 'SIMULATED'
  | 'ALREADY_REPORTED'
  | 'SOFT_DEDUP'
  | 'FILTERED'
  | 'SKIPPED'
  | 'FILTRATION_VARIANCE'
  | 'RECONCILIATION_VARIANCE'
  | 'TRANSFORMER_OUTPUT';
type TransactionEvidenceSource = 'ALL' | 'JOURNEY' | 'EXCLUSION_AUDIT' | 'RULE_HIT';
type TransactionOutcome = 'ALL' | 'SUCCESS' | 'ERROR' | 'PENDING' | 'EXCLUDED';
type TransactionStatus =
  | 'ALL'
  | 'SUCCESS'
  | 'FAILED'
  | 'ERROR'
  | 'EXCLUDED'
  | 'NOT_YET_REPORTED'
  | 'REPORTED'
  | 'NOT_REPORTED';
type TransactionEvidenceLevel =
  | 'RECORD_LEVEL'
  | 'PARTIAL_RECORD_LEVEL'
  | 'AGGREGATE_ONLY'
  | 'NO_RECORDS';
type TransactionSortDirection = 'ASC' | 'DESC';

type ReportMode = 'BATCH' | 'PERIOD';

interface BatchReportContext {
  kind: 'BATCH';
  reportGroupId: number;
  reportGroupName: string | null;
  batchId: string;
  sequenceNumber: number;
  countryCode: string;
  countryName: string;
  reportingPeriodFrom: string | null;
  reportingPeriodTo: string | null;
}

/** No single batch — a KPI (e.g. total excluded transactions) can span many batches, so this
 *  covers every batch matching the date range / report group / country filter instead. */
interface PeriodReportContext {
  kind: 'PERIOD';
  reportGroupId: number | null;
  reportGroupName: string | null;
  countryCode: string;
  countryName: string;
  fromDate: string;
  toDate: string;
  batchCount: number;
}

type ReportContext = BatchReportContext | PeriodReportContext;

interface TransactionEvidenceRecord {
  recordKey: string;
  identifier: string;
  mtcn: string | null;
  batchId: string | null;
  source: Exclude<TransactionEvidenceSource, 'ALL'>;
  stage: string | null;
  status: string | null;
  outcome: Exclude<TransactionOutcome, 'ALL'>;
  comments: string | null;
  skipReason: string | null;
  exclusionReason: string | null;
  reportedBatchId: string | null;
  modifiedAt: string | null;
  processingComplete: boolean | null;
}

/** Fetched per transaction when its row is expanded -- see GET /api/v1/transactions/detail. */
interface TransactionEvidenceDetail {
  recordKey: string;
  identifier: string;
  mtcn: string | null;
  batchId: string | null;
  senderName: string | null;
  senderCity: string | null;
  senderCountry: string | null;
  senderPhone: string | null;
  senderDateOfBirth: string | null;
  senderIdType: string | null;
  senderIdNumber: string | null;
  receiverName: string | null;
  receiverCity: string | null;
  receiverCountry: string | null;
  receiverPhone: string | null;
  receiverDateOfBirth: string | null;
  receiverIdType: string | null;
  receiverIdNumber: string | null;
  currencyAmount: number | null;
  currencyCode: string | null;
  transactionDate: string | null;
  sendDate: string | null;
  transactionSide: string | null;
  transactionStatus: string | null;
  transactionSubStatus: string | null;
  comments: string | null;
  skipReason: string | null;
  ruleId: string | null;
  exclusionReason: string | null;
  exclusionStrategy: string | null;
  reportingTimestamp: string | null;
  txnSource: string | null;
  activityType: string | null;
  galacticId: string | null;
  bucketId: number | null;
  attemptId: number | null;
  ruleHitsJson: string | null;
}

interface EvidenceDetail {
  primary: string;
  extras: string[];
}

interface RuleHitSummary {
  ruleId: string | null;
  isReported: boolean | null;
  reportingTimestamp: string | null;
  bucketId: number | null;
  attemptId: number | null;
}

/** Normalized shape the template renders, after tagging whichever backend response arrived
 *  (batch-scoped or period-wide) with its context's `kind`. */
interface TransactionReportResponse {
  context: ReportContext;
  metric: TransactionMetric | null;
  metricLabel: string;
  aggregateCount: number;
  reportedAggregateCount?: number;
  aggregateCountMismatch?: boolean;
  availableRecordCount: number;
  matchingRecordCount: number;
  evidenceLevel: TransactionEvidenceLevel;
  evidenceMessage: string;
  transactions: TransactionEvidenceRecord[];
  search: string;
  source?: TransactionEvidenceSource;
  outcome: TransactionOutcome;
  status: TransactionStatus;
  sortDirection?: TransactionSortDirection;
  page: number;
  size: number;
}

/** Raw shape of GET /api/v1/transactions/report, before the context is tagged 'BATCH'. */
interface RawBatchReportResponse {
  context: Omit<BatchReportContext, 'kind'>;
  metric: TransactionMetric;
  metricLabel: string;
  aggregateCount: number;
  reportedAggregateCount: number;
  aggregateCountMismatch: boolean;
  availableRecordCount: number;
  matchingRecordCount: number;
  evidenceLevel: TransactionEvidenceLevel;
  evidenceMessage: string;
  transactions: TransactionEvidenceRecord[];
  search: string;
  source: TransactionEvidenceSource;
  outcome: TransactionOutcome;
  status: TransactionStatus;
  sortDirection?: TransactionSortDirection;
  page: number;
  size: number;
}

/** Raw shape of GET /api/v1/transactions/period-report, before the context is tagged 'PERIOD'. */
interface RawPeriodReportResponse {
  context: Omit<PeriodReportContext, 'kind'>;
  metricLabel: string;
  aggregateCount: number;
  availableRecordCount: number;
  matchingRecordCount: number;
  evidenceLevel: TransactionEvidenceLevel;
  evidenceMessage: string;
  transactions: TransactionEvidenceRecord[];
  search: string;
  outcome: TransactionOutcome;
  status: TransactionStatus;
  sortDirection?: TransactionSortDirection;
  page: number;
  size: number;
}

@Component({
  standalone: true,
  imports: [DatePipe, DecimalPipe, RouterLink],
  templateUrl: './transaction-report.component.html',
  styleUrl: './transaction-report.component.css'
})
export class TransactionReportComponent implements OnInit {
  private readonly http = inject(HttpClient);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly destroyRef = inject(DestroyRef);

  readonly report = signal<TransactionReportResponse | null>(null);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);
  readonly hasContext = signal(false);
  readonly mode = signal<ReportMode | null>(null);
  // True only when reached from the Transactions Overview dashboard tiles (Excluded/Not Reported),
  // which are backed by a dedicated identifier-grain query, not the batch-grain evidence pipeline
  // every other status uses — restricting the dropdown to just those two keeps the UI honest about
  // which query is actually running.
  readonly overviewOnly = signal(false);
  // Broader than overviewOnly above (also true for the Reported/Excluded totals reached from that
  // page's daily/weekly/monthly trend chart, which use the ordinary batch-grain pipeline and so
  // must NOT get overviewOnly's dropdown restriction) -- tracks only where "Back to dashboard"
  // should return to, independent of that restriction. See goBack().
  readonly returnToTransactionView = signal(false);
  // True only for the Report Groups Requiring Attention table's "Excluded" column, whose own
  // number is SUM(excluded_txn) over these exact batches — a different, simpler definition than
  // overviewOnly's "ever excluded, across every batch in this window" rollup. Threaded straight
  // to the backend so evidence here actually reconciles with the number that was clicked.
  readonly batchScopedExcluded = signal(false);

  readonly reportGroupId = signal<number | null>(null);
  readonly batchId = signal('');
  // Period mode only -- a substring filter against batch ID, distinct from `batchId` above (which
  // identifies the single batch a BATCH-mode report is scoped to). batchIdOptions is every batch ID
  // in the current period scope, fetched once per scope rather than per keystroke; the dropdown
  // below narrows it client-side as the user types. A custom listbox rather than <input list> +
  // <datalist> -- that native popup is unstyleable OS/browser chrome, not part of the page.
  readonly batchIdFilter = signal('');
  readonly batchIdOptions = signal<string[]>([]);
  readonly batchDropdownOpen = signal(false);
  readonly filteredBatchIdOptions = computed(() => {
    const query = this.batchIdFilter().trim().toLowerCase();
    return query ? this.batchIdOptions().filter(id => id.toLowerCase().includes(query)) : this.batchIdOptions();
  });
  private lastBatchIdOptionsScopeKey = '';
  readonly sequenceNumber = signal<number | null>(null);
  // Period mode only — the date range / country a KPI's total was computed over, when there is
  // no single batch to scope the report to.
  readonly fromDate = signal('');
  readonly toDate = signal('');
  readonly country = signal('ALL');
  readonly metric = signal<TransactionMetric>('ALL');
  readonly search = signal('');
  readonly source = signal<TransactionEvidenceSource>('ALL');
  readonly status = signal<TransactionStatus>('ALL');
  // Period mode only, reached from the Transactions Overview dashboard's breakdown cards -- narrows
  // EXCLUDED or NOT_REPORTED to one reason bucket, matching the exact slice the clicked legend row
  // counted (see TransactionReportRepository#reportingTarget).
  readonly reason = signal('');
  readonly sortDirection = signal<TransactionSortDirection>('DESC');
  readonly page = signal(0);
  readonly size = signal(25);
  readonly expandedRecordKey = signal<string | null>(null);
  // Only one row is ever open, but keep a per-row map so reopening a row within the same view does
  // not refetch. Cleared whenever the underlying list is reloaded, so a filter or scope change can
  // never show a detail resolved under the previous one.
  readonly detailCache = signal<Record<string, TransactionEvidenceDetail>>({});
  readonly detailLoadingKey = signal<string | null>(null);
  readonly detailErrorKey = signal<string | null>(null);
  private detailGeneration = 0;
  private readonly pendingDetails = new Set<string>();

  ngOnInit(): void {
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(params => {
      this.readRouteState(params);
      if (this.hasContext()) {
        this.loadReport();
      } else {
        this.report.set(null);
        this.loading.set(false);
      }
    });
  }

  setSearch(event: Event): void {
    this.search.set((event.target as HTMLInputElement).value);
  }

  setStatus(event: Event): void {
    this.status.set((event.target as HTMLSelectElement).value as TransactionStatus);
  }

  setBatchIdFilter(event: Event): void {
    this.batchIdFilter.set((event.target as HTMLInputElement).value);
    this.batchDropdownOpen.set(true);
  }

  openBatchDropdown(): void {
    this.batchDropdownOpen.set(true);
  }

  /** Delayed rather than immediate: an option button's own (click) fires after this input's blur,
   *  so closing synchronously would unmount the button before the click is handled. The button's
   *  own (mousedown) already prevents the blur that would otherwise trigger this on a mouse
   *  selection; the delay here is the fallback for however else focus could leave (Tab, clicking
   *  elsewhere). */
  closeBatchDropdown(): void {
    setTimeout(() => this.batchDropdownOpen.set(false), 150);
  }

  selectBatchId(id: string): void {
    this.batchIdFilter.set(id);
    this.batchDropdownOpen.set(false);
  }

  /** Server-side sort, same as every other filter here — a page is only a small window into a
   *  much larger, independently-ordered result set (this report can back a table with millions
   *  of rows in production), so sorting can only ever be correct if it's applied before LIMIT/
   *  OFFSET at the database, not by re-arranging whatever one page happened to already load. */
  toggleSortDirection(): void {
    this.updateRoute({
      sortDirection: this.sortDirection() === 'DESC' ? 'ASC' : 'DESC',
      page: 0
    });
  }

  applyFilters(event: SubmitEvent): void {
    event.preventDefault();
    this.updateRoute({
      search: this.search().trim() || null,
      status: this.status(),
      periodBatchId: this.batchIdFilter().trim() || null,
      page: 0
    });
  }

  clearFilters(): void {
    this.batchIdFilter.set('');
    this.updateRoute({ search: null, status: 'ALL', periodBatchId: null, page: 0 });
  }

  previousPage(): void {
    if (this.page() > 0) {
      this.updateRoute({ page: this.page() - 1 });
    }
  }

  nextPage(report: TransactionReportResponse): void {
    if ((this.page() + 1) * this.size() < report.matchingRecordCount) {
      this.updateRoute({ page: this.page() + 1 });
    }
  }

  goToPage(pageNumber: number): void {
    this.updateRoute({ page: pageNumber - 1 });
  }

  totalPages(report: TransactionReportResponse): number {
    return Math.max(1, Math.ceil(report.matchingRecordCount / this.size()));
  }

  pageNumbers(report: TransactionReportResponse): (number | '…')[] {
    const total = this.totalPages(report);
    const current = this.page() + 1;
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

  pageRange(report: TransactionReportResponse): string {
    if (report.matchingRecordCount === 0) {
      return '0 records';
    }
    const first = this.page() * this.size() + 1;
    const last = Math.min(first + report.transactions.length - 1, report.matchingRecordCount);
    return `${first}–${last} of ${report.matchingRecordCount}`;
  }

  /** Navigates to this report's logical parent view directly, rather than via browser history.
   *  Every filter/sort/search/page change on this page pushes its own history entry (see
   *  updateRoute()) without leaving the route, so location.back() used to require one click per
   *  interaction made on this page before it ever left it -- a route-based destination is correct
   *  regardless of how many in-page interactions happened first.
   *  In BATCH mode this must deep-link back to the specific batch (same as openBatchView()) --
   *  navigating to a bare /batches/explorer drops all context and lands on an empty explorer list
   *  instead of the batch the user actually came from. Likewise, a PERIOD-mode report reached from
   *  the Transactions Overview page (returnToTransactionView -- see readRouteState) must return
   *  there with its own filters, not to the unrelated, unfiltered Batch View dashboard. */
  goBack(): void {
    if (this.mode() === 'BATCH') {
      this.openBatchView();
      return;
    }
    if (this.returnToTransactionView()) {
      void this.router.navigate(['/transaction-view'], {
        queryParams: {
          country: this.country(),
          reportGroupId: this.reportGroupId(),
          fromDate: this.fromDate(),
          toDate: this.toDate()
        }
      });
      return;
    }
    void this.router.navigate(['/batches']);
  }

  openBatchView(): void {
    // The batch explorer's fromDate/toDate filter is matched against the reconciliation row's
    // created_timestamp (when the batch was processed), not its reporting period — the two can be
    // weeks apart. A deliberately wide range keeps this deep link working regardless of either
    // date, since batchId + reportGroupId + sequenceNumber already pin down the exact batch.
    void this.router.navigate(['/batches/explorer'], {
      queryParams: {
        reportGroupId: this.reportGroupId(),
        batchId: this.batchId(),
        sequenceNumber: this.sequenceNumber(),
        fromDate: '2000-01-01',
        toDate: '2099-12-31'
      }
    });
  }

  /** Same deep link as openBatchView(), but for an arbitrary batch id referenced from a row (its
   *  own batchId, or a "previously reported in" batch) — not necessarily the batch under
   *  investigation. Omits sequenceNumber since evidence rows don't carry the target batch's
   *  reconciliation sequence; the explorer falls back to the first (only) match for that batch id.
   *  Also omits reportGroupId in period mode: the page's own reportGroupId filter (often null, for
   *  "all report groups") isn't necessarily the group that batch actually belongs to when the
   *  period spans more than one — batchId text-matches uniquely on its own. */
  viewBatch(batchId: string | null, event: Event): void {
    event.stopPropagation();
    if (!batchId) {
      return;
    }
    const queryParams: Record<string, string | number | null> = {
      batchId,
      fromDate: '2000-01-01',
      toDate: '2099-12-31'
    };
    if (this.mode() === 'BATCH') {
      queryParams['reportGroupId'] = this.reportGroupId();
    }
    void this.router.navigate(['/batches/explorer'], { queryParams });
  }

  viewReportConfig(reportGroupId: number | null, event: Event): void {
    event.stopPropagation();
    if (reportGroupId === null) {
      return;
    }
    void this.router.navigate(['/report-config'], {
      queryParams: { reportGroupId, status: 'ALL' }
    });
  }

  /** "PORTUGAL OBJECTIVE" / "Report group 123" when scoped to one group, "All report groups"
   *  when a period-mode report spans every group matching its date range/country filter. */
  contextGroupLabel(context: ReportContext): string {
    if (context.reportGroupId === null) {
      return 'All report groups';
    }
    return context.reportGroupName || `Report group ${context.reportGroupId}`;
  }

  recordDetail(record: TransactionEvidenceRecord): EvidenceDetail {
    if (record.source === 'RULE_HIT') {
      return { primary: record.status === 'REPORTED' ? 'Reported' : 'Not yet reported', extras: [] };
    }
    const raw = record.exclusionReason ?? record.skipReason ?? record.comments;
    if (!raw) {
      return {
        primary: record.processingComplete === false ? 'Processing incomplete' : 'No additional detail',
        extras: []
      };
    }
    const issues = this.parseIssueList(raw);
    if (issues) {
      const [first, ...rest] = issues;
      const extras: string[] = [];
      if (first.field) { extras.push(`Field: ${first.field}`); }
      if (first.ruleSet) { extras.push(`Rule set: ${this.humanize(first.ruleSet)}`); }
      if (first.errorCode) { extras.push(`Error code: ${first.errorCode}`); }
      if (rest.length > 0) { extras.push(`+${rest.length} more issue${rest.length > 1 ? 's' : ''}`); }
      return { primary: first.message, extras };
    }
    return { primary: this.humanizeIfCode(raw), extras: [] };
  }

  /** Same shape as recordDetail, but scoped strictly to record.skipReason (not the
   *  exclusionReason/comments fallback chain) — used for the expanded "Skip reason" field so it
   *  renders the underlying exception JSON as readable text instead of the raw payload. */
  skipReasonDetail(detail: TransactionEvidenceDetail | null): EvidenceDetail {
    if (!detail?.skipReason) {
      return { primary: 'Not available', extras: [] };
    }
    const issues = this.parseIssueList(detail.skipReason);
    if (issues) {
      const [first, ...rest] = issues;
      const extras: string[] = [];
      if (first.field) { extras.push(`Field: ${first.field}`); }
      if (first.ruleSet) { extras.push(`Rule set: ${this.humanize(first.ruleSet)}`); }
      if (first.errorCode) { extras.push(`Error code: ${first.errorCode}`); }
      if (rest.length > 0) { extras.push(`+${rest.length} more issue${rest.length > 1 ? 's' : ''}`); }
      return { primary: first.message, extras };
    }
    return { primary: this.humanizeIfCode(detail.skipReason), extras: [] };
  }

  /** Converts a SCREAMING_SNAKE_CASE / mixed(PAREN) code into "Screaming Snake Case (Paren)". */
  humanize(value: string | null | undefined): string {
    if (!value) {
      return 'Not available';
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

  ruleIdsDisplay(detail: TransactionEvidenceDetail | null): string {
    if (detail?.ruleId) {
      return detail.ruleId;
    }
    const ids = [...new Set(this.ruleHits(detail).map(hit => hit.ruleId).filter((id): id is string => !!id))];
    return ids.length > 0 ? ids.join(', ') : 'Not available';
  }

  toggleExpanded(record: TransactionEvidenceRecord): void {
    const closing = this.expandedRecordKey() === record.recordKey;
    this.expandedRecordKey.set(closing ? null : record.recordKey);
    if (!closing) {
      this.loadDetail(record);
    }
  }

  detailFor(record: TransactionEvidenceRecord): TransactionEvidenceDetail | null {
    return this.detailCache()[record.recordKey] ?? null;
  }

  /**
   * Personal data lives behind this call rather than in the list response, so opening a row is the
   * action that reads it. Cached per row for the life of the current result set; `loadTransactions`
   * clears the cache, so a detail resolved under one filter/scope can never be shown under another.
   */
  loadDetail(record: TransactionEvidenceRecord): void {
    if (this.detailCache()[record.recordKey]) {
      return;
    }
    if (this.pendingDetails.has(record.recordKey)) {
      this.detailLoadingKey.set(record.recordKey);
      return;
    }
    const reportGroupId = this.reportGroupId();
    // A period view can legitimately span every report group, so only the batch view genuinely
    // needs one. Anything else missing is surfaced as an error rather than returned from silently:
    // a guard that just bails renders an empty panel with no explanation, which is how this
    // shipped broken the first time.
    if (!record.batchId || (this.mode() === 'BATCH' && reportGroupId === null)) {
      this.detailLoadingKey.set(null);
      this.detailErrorKey.set(record.recordKey);
      return;
    }
    this.detailErrorKey.set(null);
    this.detailLoadingKey.set(record.recordKey);

    let params = new HttpParams()
      .set('scope', this.mode() === 'BATCH' ? 'BATCH' : 'PERIOD')
      .set('batchId', record.batchId)
      .set('identifier', record.identifier)
      .set('recordKey', record.recordKey);
    if (reportGroupId !== null) {
      params = params.set('reportGroupId', reportGroupId);
    }
    if (this.mode() === 'BATCH') {
      // The same filters the list ran under: the panel expands a specific row, so it has to be
      // resolved the same way that row was.
      params = params
        .set('metric', this.metric())
        .set('source', this.source())
        .set('status', this.status());
    } else {
      params = params
        .set('fromDate', this.fromDate())
        .set('toDate', this.toDate())
        .set('country', this.country())
        .set('status', this.status())
        .set('reason', this.reason())
        .set('batchScopedExcluded', this.batchScopedExcluded())
        .set('batchIdFilter', this.batchIdFilter().trim());
    }

    const generation = this.detailGeneration;
    this.pendingDetails.add(record.recordKey);
    this.http.get<TransactionEvidenceDetail>('/api/v1/transactions/detail', { params })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: detail => {
        if (generation !== this.detailGeneration) { return; }
        this.pendingDetails.delete(record.recordKey);
        this.detailCache.update(cache => ({ ...cache, [record.recordKey]: detail }));
        if (this.detailLoadingKey() === record.recordKey) { this.detailLoadingKey.set(null); }
      },
      error: () => {
        if (generation !== this.detailGeneration) { return; }
        this.pendingDetails.delete(record.recordKey);
        if (this.detailLoadingKey() === record.recordKey) { this.detailLoadingKey.set(null); }
        if (this.expandedRecordKey() === record.recordKey) { this.detailErrorKey.set(record.recordKey); }
      }
    });
  }

  ruleHits(detail: TransactionEvidenceDetail | null): RuleHitSummary[] {
    if (!detail?.ruleHitsJson) {
      return [];
    }
    try {
      const parsed = JSON.parse(detail.ruleHitsJson);
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  /** A "code" is a machine constant (SCREAMING_SNAKE_CASE, dotted IDs, etc) — humanize only those,
   *  leaving free-text config values (e.g. an exclusion reason sentence) untouched. Public so the
   *  PERIOD-mode context strip's "Reason" line (set when arriving from the dashboard's Top
   *  Exclusion Reasons legend) can reuse it directly rather than re-deriving the same display text
   *  a second way. */
  humanizeIfCode(value: string): string {
    return /^[A-Z0-9_()./-]+$/.test(value) ? this.humanize(value) : value;
  }

  /** Parses a skip/exclusion reason that is a JSON array of exception objects (or plain strings)
   *  into a simple {message, field, ruleSet, errorCode} shape the template can render legibly.
   *  Returns null when the raw value isn't such a JSON array, so callers fall back to plain text. */
  private parseIssueList(
    raw: string
  ): { message: string; field: string | null; ruleSet: string | null; errorCode: string | null }[] | null {
    let parsed: unknown;
    try {
      parsed = JSON.parse(raw);
    } catch {
      return null;
    }
    if (!Array.isArray(parsed) || parsed.length === 0) {
      return null;
    }
    return parsed.map(entry => {
      if (entry && typeof entry === 'object') {
        const obj = entry as Record<string, unknown>;
        const message = (obj['Exception'] ?? obj['exception'] ?? obj['Message'] ?? obj['message']) as
          | string
          | undefined;
        return {
          message: message ?? JSON.stringify(obj),
          field: ((obj['Path'] ?? obj['path']) as string | undefined) ?? null,
          ruleSet: ((obj['RuleSetName'] ?? obj['ruleSetName']) as string | undefined) ?? null,
          errorCode: ((obj['ErrorCode'] ?? obj['errorCode']) as string | undefined) ?? null
        };
      }
      return { message: String(entry), field: null, ruleSet: null, errorCode: null };
    });
  }

  formatCurrency(detail: TransactionEvidenceDetail | null): string {
    if (!detail || detail.currencyAmount === null) {
      return 'Not available';
    }
    return detail.currencyCode
      ? `${detail.currencyCode} ${detail.currencyAmount.toLocaleString()}`
      : detail.currencyAmount.toLocaleString();
  }

  private loadReport(): void {
    this.detailGeneration++;
    this.pendingDetails.clear();
    // A new result set invalidates every cached detail: the filters or scope that resolved them may
    // have changed, and a panel must never describe a row under different conditions than the list.
    this.detailCache.set({});
    this.detailLoadingKey.set(null);
    this.detailErrorKey.set(null);
    this.expandedRecordKey.set(null);
    this.loading.set(true);
    this.error.set(null);
    this.report.set(null);

    if (this.mode() === 'BATCH') {
      this.loadBatchReport();
    } else if (this.mode() === 'PERIOD') {
      this.loadPeriodReport();
    }
  }

  private loadBatchReport(): void {
    const params = new HttpParams()
      .set('reportGroupId', this.reportGroupId()!)
      .set('batchId', this.batchId())
      .set('sequenceNumber', this.sequenceNumber()!)
      .set('metric', this.metric())
      .set('search', this.search().trim())
      .set('source', this.source())
      .set('status', this.status())
      .set('sortDirection', this.sortDirection())
      .set('page', this.page())
      .set('size', this.size());

    this.http.get<RawBatchReportResponse>('/api/v1/transactions/report', { params }).subscribe({
      next: raw => {
        this.report.set({
          ...raw,
          context: { kind: 'BATCH', ...raw.context }
        });
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('The transaction evidence report could not be loaded.');
      }
    });
  }

  /** Period mode: no batchId, so there is no single reconciliation batch to fetch evidence for —
   *  scoped instead by date range and the optional report group/country filters, matching however
   *  many batches the underlying KPI actually summed. See openExcludedTransactionsExplorer() in
   *  home.component.ts for where this is linked from. */
  private loadPeriodReport(): void {
    this.maybeLoadBatchIdOptions();

    let params = new HttpParams()
      .set('fromDate', this.fromDate())
      .set('toDate', this.toDate())
      .set('country', this.country())
      .set('batchId', this.batchIdFilter().trim())
      .set('search', this.search().trim())
      .set('status', this.status())
      .set('batchScopedExcluded', this.batchScopedExcluded())
      .set('sortDirection', this.sortDirection())
      .set('page', this.page())
      .set('size', this.size());
    if (this.reportGroupId() !== null) {
      params = params.set('reportGroupId', this.reportGroupId()!);
    }
    if (this.reason().trim()) {
      params = params.set('reason', this.reason().trim());
    }

    this.http.get<RawPeriodReportResponse>('/api/v1/transactions/period-report', { params }).subscribe({
      next: raw => {
        this.report.set({
          ...raw,
          metric: null,
          context: { kind: 'PERIOD', ...raw.context }
        });
        this.loading.set(false);
      },
      error: () => {
        this.loading.set(false);
        this.error.set('The transaction evidence report could not be loaded.');
      }
    });
  }

  /** The batch picker's option list only depends on date range / country / report group, not on
   *  search/status/page/sort -- refetching it on every filter change or page turn would be wasted
   *  work and a flickering dropdown, so this is keyed on just that narrower scope and skipped when
   *  unchanged. */
  private maybeLoadBatchIdOptions(): void {
    const scopeKey = `${this.fromDate()}|${this.toDate()}|${this.country()}|${this.reportGroupId() ?? 'ALL'}`;
    if (scopeKey === this.lastBatchIdOptionsScopeKey) {
      return;
    }
    this.lastBatchIdOptionsScopeKey = scopeKey;

    let params = new HttpParams()
      .set('fromDate', this.fromDate())
      .set('toDate', this.toDate())
      .set('country', this.country());
    if (this.reportGroupId() !== null) {
      params = params.set('reportGroupId', this.reportGroupId()!);
    }

    this.http.get<string[]>('/api/v1/transactions/period-report/batches', { params }).subscribe({
      next: ids => this.batchIdOptions.set(ids),
      error: () => this.batchIdOptions.set([])
    });
  }

  private readRouteState(params: ParamMap): void {
    const reportGroupId = Number(params.get('reportGroupId'));
    const sequenceNumber = Number(params.get('sequenceNumber'));
    const batchId = params.get('batchId')?.trim() ?? '';
    const fromDate = params.get('fromDate')?.trim() ?? '';
    const toDate = params.get('toDate')?.trim() ?? '';

    this.reportGroupId.set(Number.isInteger(reportGroupId) && reportGroupId > 0 ? reportGroupId : null);
    this.sequenceNumber.set(Number.isInteger(sequenceNumber) && sequenceNumber > 0 ? sequenceNumber : null);
    this.batchId.set(batchId);
    this.fromDate.set(fromDate);
    this.toDate.set(toDate);
    this.country.set(params.get('country')?.trim() || 'ALL');
    this.metric.set(this.parseMetric(params.get('metric')));
    this.search.set(params.get('search') ?? '');
    this.source.set(this.parseSource(params.get('source')));
    this.status.set(this.parseStatus(params.get('status')));
    this.reason.set(params.get('reason')?.trim() ?? '');
    this.sortDirection.set(params.get('sortDirection') === 'ASC' ? 'ASC' : 'DESC');
    this.page.set(Math.max(0, Number(params.get('page') ?? 0) || 0));
    this.overviewOnly.set(params.get('view') === 'overview');
    this.returnToTransactionView.set(params.get('origin') === 'overview');
    this.batchScopedExcluded.set(params.get('batchScopedExcluded') === 'true');
    this.batchIdFilter.set(params.get('periodBatchId')?.trim() ?? '');

    if (batchId && this.reportGroupId() !== null && this.sequenceNumber() !== null) {
      this.mode.set('BATCH');
    } else if (fromDate && toDate) {
      this.mode.set('PERIOD');
    } else {
      this.mode.set(null);
    }
    this.hasContext.set(this.mode() !== null);
  }

  private updateRoute(queryParams: Record<string, string | number | null>): void {
    void this.router.navigate([], {
      relativeTo: this.route,
      queryParams,
      queryParamsHandling: 'merge'
    });
  }

  private parseMetric(value: string | null): TransactionMetric {
    const metrics: TransactionMetric[] = [
      'SELECTED', 'ATTEMPTS_FOUND', 'MISSING', 'ACTIVITY_MISSING', 'EXPECTED_ELIGIBLE', 'ACTUAL_ELIGIBLE',
      'TRANSFORMED', 'FAILED', 'EXPECTED_REPORTABLE', 'ACTUAL_REPORTABLE', 'EXCLUDED',
      'SIMULATED', 'ALREADY_REPORTED', 'SOFT_DEDUP', 'FILTERED', 'SKIPPED',
      'FILTRATION_VARIANCE', 'RECONCILIATION_VARIANCE', 'TRANSFORMER_OUTPUT'
    ];
    return metrics.includes(value as TransactionMetric) ? (value as TransactionMetric) : 'ALL';
  }

  private parseSource(value: string | null): TransactionEvidenceSource {
    return value === 'JOURNEY' || value === 'EXCLUSION_AUDIT' || value === 'RULE_HIT'
      ? value
      : 'ALL';
  }

  private parseStatus(value: string | null): TransactionStatus {
    return value === 'SUCCESS' ||
      value === 'FAILED' ||
      value === 'ERROR' ||
      value === 'EXCLUDED' ||
      value === 'NOT_YET_REPORTED' ||
      value === 'REPORTED' ||
      value === 'NOT_REPORTED'
      ? value
      : 'ALL';
  }
}
