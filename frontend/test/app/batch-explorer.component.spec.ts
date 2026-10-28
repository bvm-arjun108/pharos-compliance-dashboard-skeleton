import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { BatchExplorerComponent } from '../../src/app/batch-explorer.component';
import { RouteStub, RouterStub } from '../support/router-stubs';

const queueItem = (overrides: Record<string, unknown> = {}) => ({
  reportGroupId: 1,
  reportGroupName: 'RG 1',
  batchId: 'BIN 1',
  sequenceNumber: 1,
  countryCode: 'US',
  countryName: 'United States',
  reportingPeriodFrom: '2026-09-01T00:00:00',
  reportingPeriodTo: '2026-09-30T00:00:00',
  startedAt: '2026-09-01T10:00:00',
  completedAt: '2026-09-01T10:05:00',
  status: 'ATTENTION',
  transformationFailures: 1,
  reportedTransformationFailures: 2,
  transformationFailureMismatch: true,
  missingAttempts: 1,
  activityMissing: 1,
  filtrationErrors: 0,
  reconciliationImbalance: 0,
  transformerOutput: 5,
  excludedTransactions: 2,
  duplicateTransactions: 1,
  simulatedTransactions: 1,
  softDedupTransactions: 1,
  totalIssues: 3,
  ...overrides
});

const explorer = (batches: ReturnType<typeof queueItem>[], overrides: Record<string, unknown> = {}) => ({
  summary: {
    allBatches: 5,
    successfulBatches: 3,
    attentionBatches: 2,
    activityMissingBatches: 1,
    missingAttemptBatches: 1,
    transformationBatches: 1,
    duplicateTransactionBatches: 1,
    exclusionBatches: 1,
    simulatedTransactionBatches: 1,
    softDedupBatches: 1
  },
  batches,
  matchingBatches: batches.length,
  page: 0,
  size: 50,
  fromDate: '2026-09-01',
  toDate: '2026-09-30',
  status: 'ALL',
  issueType: 'ALL',
  batchId: '',
  country: 'ALL',
  reportGroupId: null,
  reportGroupName: null,
  metricFocus: 'DEFAULT',
  ...overrides
});

const details = (overrides: Record<string, unknown> = {}) => ({
  reportGroupId: 1,
  reportGroupName: 'RG 1',
  batchId: 'BIN 1',
  sequenceNumber: 1,
  countryCode: 'US',
  countryName: 'United States',
  reportingPeriodFrom: '2026-09-01T00:00:00',
  reportingPeriodTo: '2026-09-30T00:00:00',
  startedAt: '2026-09-01T10:00:00',
  completedAt: '2026-09-01T10:05:00',
  durationSeconds: 305,
  operationalStatus: 'COMPLETED',
  status: 'ATTENTION',
  transformationFailures: 1,
  reportedTransformationFailures: 1,
  transformationFailureMismatch: false,
  missingAttempts: 1,
  activityMissing: 1,
  duplicateTransactions: 0,
  filtrationErrors: 0,
  reconciliationImbalance: 0,
  totalIssues: 3,
  selectedTransactions: 10,
  transactionAttemptsFound: 9,
  expectedReportableTransactions: 8,
  actualReportableTransactions: 8,
  expectedTransformationAttempts: 8,
  actualTransformationAttempts: 8,
  transformedActivities: 7,
  transformationBalanced: true,
  transformerOutput: 5,
  finalDownstreamReported: null,
  excludedTransactions: 1,
  simulatedTransactions: 1,
  alreadyReportedTransactions: 1,
  softDedupTransactions: 1,
  journeyAvailable: true,
  ruleHitsAvailable: false,
  exclusionsAvailable: true,
  reportSelectionVersionId: 2,
  transformerVersionId: 'tv/1',
  ...overrides
});

const configDetails = {
  identity: { reportGroupId: 1, reportGroupName: 'RG 1', countryCode: 'US', countryName: 'United States', reportType: 'CTR', active: true },
  versioning: { reportSelectionVersionId: 2, transformerVersionId: 'tv/1', modifiedAt: null },
  mapping: { serviceName: 'svc' }
};

describe('BatchExplorerComponent', () => {
  let fixture: ComponentFixture<BatchExplorerComponent>;
  let component: BatchExplorerComponent;
  let http: HttpTestingController;
  let route: RouteStub;
  let router: RouterStub;

  beforeEach(async () => {
    route = new RouteStub();
    router = new RouterStub();
    await TestBed.configureTestingModule({
      imports: [BatchExplorerComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(BatchExplorerComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const start = (query: Record<string, string> = {}) => {
    route.setQuery(query);
    fixture.detectChanges();
  };
  const answerLookups = () => {
    http.expectOne('/api/v1/batches/filter-options').flush({ countries: [{ code: 'US', name: 'United States' }] });
    http.expectOne('/api/v1/report-configs/report-groups').flush([
      { reportGroupId: 1, reportGroupName: 'RG 1', countryCode: 'US' },
      { reportGroupId: 2, reportGroupName: 'RG 2', countryCode: 'CA' }
    ]);
  };
  const queueRequest = () => http.expectOne(req => req.url === '/api/v1/batches');

  it('loads the queue, selects the first batch and loads its details and report config', () => {
    start();
    answerLookups();
    const request = queueRequest();
    expect(request.request.params.get('status')).toBe('ALL');
    expect(request.request.params.get('reportGroupId')).toBeNull();
    request.flush(explorer([queueItem(), queueItem({ batchId: 'B2' })]));
    http.expectOne('/api/v1/batches/1/BIN%201/1').flush(details());
    http.expectOne('/api/v1/report-configs/1/2/tv%2F1').flush(configDetails);
    fixture.detectChanges();

    expect(component.selectedBatch()?.batchId).toBe('BIN 1');
    expect(component.selectedDetails()?.durationSeconds).toBe(305);
    expect(component.reportConfigSummary()?.mappingServiceName).toBe('svc');
    expect(component.queueLoading()).toBe(false);
    expect(component.detailLoading()).toBe(false);
    expect(component.reportConfigLoading()).toBe(false);
  });

  it('honours a deep link to a specific batch and report group', () => {
    start({ reportGroupId: '1', batchId: ' B2 ', sequenceNumber: '3', metricFocus: 'EXCLUDED', status: 'ATTENTION', issueType: 'EXCLUSION', page: '2' });
    answerLookups();
    const request = queueRequest();
    expect(request.request.params.get('reportGroupId')).toBe('1');
    expect(request.request.params.get('batchId')).toBe('B2');
    expect(request.request.params.get('page')).toBe('2');
    request.flush(explorer([queueItem({ batchId: 'B1' }), queueItem({ batchId: 'B2', sequenceNumber: 3 })]));
    http.expectOne('/api/v1/batches/1/B2/3').flush(details({ reportSelectionVersionId: null, transformerVersionId: null }));
    expect(component.selectedBatch()?.sequenceNumber).toBe(3);
    expect(component.reportConfigSummary()).toBeNull();
  });

  it('falls back to the first batch when the deep-linked one is not in the queue', () => {
    start({ reportGroupId: '1', batchId: 'MISSING', sequenceNumber: '9' });
    answerLookups();
    queueRequest().flush(explorer([queueItem({ batchId: 'B1' })]));
    http.expectOne('/api/v1/batches/1/B1/1').flush(details());
    http.expectOne('/api/v1/report-configs/1/2/tv%2F1').flush('x', { status: 500, statusText: 'err' });
    expect(component.selectedBatch()?.batchId).toBe('B1');
    expect(component.reportConfigSummary()).toBeNull();
    expect(component.reportConfigLoading()).toBe(false);
  });

  it('handles an empty queue and failures of each request', () => {
    start();
    http.expectOne('/api/v1/batches/filter-options').flush('x', { status: 500, statusText: 'err' });
    http.expectOne('/api/v1/report-configs/report-groups').flush('x', { status: 500, statusText: 'err' });
    queueRequest().flush(explorer([]));
    expect(component.selectedBatch()).toBeNull();
    expect(component.filterOptions().countries).toEqual([]);
    expect(component.reportGroupOptions()).toEqual([]);

    route.setQuery({ page: '1' });
    answerNone();
    queueRequest().flush('x', { status: 500, statusText: 'err' });
    expect(component.queueError()).toContain('could not be loaded');
    expect(component.response()).toBeNull();
  });

  function answerNone(): void {
    // switchMap re-runs only the queue; lookups are loaded once.
  }

  it('shows an error when the batch details fail and cancels the stale detail request', () => {
    start();
    answerLookups();
    queueRequest().flush(explorer([queueItem(), queueItem({ batchId: 'B2' })]));
    const first = http.expectOne('/api/v1/batches/1/BIN%201/1');
    component.selectBatch(queueItem({ batchId: 'B2' }));
    expect(first.cancelled).toBe(true);
    http.expectOne('/api/v1/batches/1/B2/1').flush('x', { status: 500, statusText: 'err' });
    expect(component.detailError()).toContain('could not be loaded');
    expect(component.detailLoading()).toBe(false);

    component.selectBatch(queueItem({ batchId: 'B2' }));
    http.expectNone('/api/v1/batches/1/B2/1');
  });

  it('keeps the filter fields consistent', () => {
    start();
    answerLookups();
    queueRequest().flush(explorer([]));

    component.setReportGroupValue(2);
    expect(component.country()).toBe('CA');
    component.setReportGroupValue(99);
    expect(component.reportGroupId()).toBe(99);
    expect(component.country()).toBe('CA');
    component.setCountryValue('US');
    expect(component.reportGroupId()).toBeNull();
    component.setReportGroupValue(1);
    component.setCountryValue('US');
    expect(component.reportGroupId()).toBe(1);
    component.setCountryValue('ALL');
    expect(component.reportGroupId()).toBe(1);
    component.setReportGroupValue(null);
    expect(component.filteredReportGroupOptions()).toHaveLength(2);
    component.setCountryValue('CA');
    expect(component.filteredReportGroupOptions().map(option => option.reportGroupId)).toEqual([2]);
    component.setBatchId({ target: { value: ' B9 ' } } as unknown as Event);
    expect(component.batchId()).toBe(' B9 ');
  });

  it('navigates when filters are applied, cleared or drilled', () => {
    start();
    answerLookups();
    queueRequest().flush(explorer([]));
    const preventDefault = jest.fn();
    component.setBatchId({ target: { value: ' B9 ' } } as unknown as Event);
    component.applyFilters({ preventDefault } as unknown as SubmitEvent);
    expect(preventDefault).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: expect.objectContaining({ batchId: 'B9', page: 0 }) }));
    component.setBatchId({ target: { value: '  ' } } as unknown as Event);
    component.applyFilters({ preventDefault } as unknown as SubmitEvent);
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: expect.objectContaining({ batchId: null }) }));
    component.clearFilters();
    component.clearDrilldown();
    component.selectStatus('SUCCESSFUL');
    component.selectAttentionReason('TRANSFORMATION');
    component.selectNoAttentionSignal('EXCLUSION');
    expect(router.navigate).toHaveBeenCalledTimes(7);
  });

  it('navigates to other screens', () => {
    start({ fromDate: '2026-09-01', toDate: '2026-09-30', batchId: ' b ' });
    answerLookups();
    queueRequest().flush(explorer([queueItem()]));
    http.expectOne('/api/v1/batches/1/BIN%201/1').flush(details());
    http.expectOne('/api/v1/report-configs/1/2/tv%2F1').flush(configDetails);

    component.goToDashboard();
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches'], { queryParams: { fromDate: '2026-09-01', toDate: '2026-09-30', batchId: 'b', country: 'ALL' } });
    component.openReportConfig(7);
    expect(router.navigate).toHaveBeenLastCalledWith(['/report-config'], { queryParams: { reportGroupId: 7 } });
    component.openTransactionReport('EXCLUDED');
    expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], {
      queryParams: expect.objectContaining({ reportGroupId: 1, batchId: 'BIN 1', sequenceNumber: 1, metric: 'EXCLUDED' })
    });
    component.selectedBatch.set(null);
    router.navigate.mockClear();
    component.openTransactionReport('ALL');
    expect(router.navigate).not.toHaveBeenCalled();
  });

  it('describes the current drill-down', () => {
    start();
    answerLookups();
    queueRequest().flush(explorer([]));
    expect(component.explorerTitle()).toBe('Batch Explorer');
    expect(component.drilldownFocusLabel()).toBe('All batches');
    expect(component.reportGroupContextName()).toBeNull();
    expect(component.countryContext()).toBe('All countries');

    component.status.set('SUCCESSFUL');
    expect(component.explorerTitle()).toBe('Batches Not Needing Attention');
    expect(component.drilldownFocusLabel()).toBe('Batches not needing attention');
    expect(component.showNoAttentionSignals()).toBe(true);
    component.status.set('ATTENTION');
    expect(component.explorerTitle()).toBe('Batches Needing Attention');
    expect(component.drilldownFocusLabel()).toBe('Batches needing attention');
    expect(component.showAttentionReasons()).toBe(true);
    component.status.set('ALL');
    expect(component.showAttentionReasons()).toBe(false);
    expect(component.showNoAttentionSignals()).toBe(false);

    const issues = ['ACTIVITY_MISSING', 'MISSING_ATTEMPTS', 'TRANSFORMATION', 'DUPLICATE_TRANSFORMATION', 'EXCLUSION', 'SIMULATED', 'SOFT_DEDUP'] as const;
    for (const issue of issues) {
      component.issueType.set(issue);
      expect(component.explorerTitle()).toBe(`${component.issueLabel(issue)} Batches`);
      expect(component.drilldownFocusLabel()).toBe(`${component.issueLabel(issue)} evidence`);
    }
    expect(component.showNoAttentionSignals()).toBe(true);
    component.issueType.set('TRANSFORMATION');
    expect(component.showAttentionReasons()).toBe(true);
    component.issueType.set('ALL');
    expect(component.issueLabel('ALL')).toBe('All');

    component.metricFocus.set('REPORTED');
    expect(component.explorerTitle()).toBe('Highest Transformer Output Batches');
    expect(component.drilldownFocusLabel()).toContain('transformer output');
    component.metricFocus.set('EXCLUDED');
    expect(component.explorerTitle()).toBe('Highest Excluded Transactions Batches');
    expect(component.drilldownFocusLabel()).toContain('excluded');

    component.reportGroupId.set(5);
    expect(component.reportGroupContextName()).toBe('Report Group 5');
    component.response.set(explorer([], { reportGroupName: 'Named' }) as never);
    expect(component.reportGroupContextName()).toBe('Named');
    component.country.set('US');
    expect(component.countryContext()).toBe('United States');
    component.country.set('ZZ');
    expect(component.countryContext()).toBe('ZZ');
  });

  it('formats numbers, periods and keys', () => {
    expect(component.formatDuration(65)).toBe('1m 05s');
    expect(component.barShare(5, 0)).toBe(0);
    expect(component.barShare(5, 10)).toBe(50);
    expect(component.barShare(50, 10)).toBe(100);
    expect(component.barRemainder(10, 3, 4)).toBe(3);
    expect(component.barRemainder(1, 3)).toBe(0);
    expect(component.batchKey(null)).toBe('');
    expect(component.batchKey(queueItem() as never)).toBe('1:BIN 1:1');
    const d = details() as never;
    expect(component.filteredTransactions(d)).toBe(1 + 1 + 1 + 1 + 1 + 1);
    expect(component.skippedTotal(d)).toBe(3);
    expect(component.reportPeriodLabel(d)).toBe('Report period 2026-09-01 – 2026-09-30');
    expect(component.reportPeriodLabel(details({ reportingPeriodTo: '2026-09-01T00:00:00' }) as never)).toBe('Report period 2026-09-01');
    expect(component.reportPeriodLabel(details({ reportingPeriodTo: null }) as never)).toBe('Report period 2026-09-01');
    expect(component.reportPeriodLabel(details({ reportingPeriodFrom: null, reportingPeriodTo: null }) as never)).toBe('Reporting period unavailable');
  });

  it('ignores invalid route values', () => {
    start({ status: 'x', issueType: 'x', metricFocus: 'x', reportGroupId: '-4', sequenceNumber: 'abc', page: '-3' });
    answerLookups();
    queueRequest().flush(explorer([]));
    expect(component.status()).toBe('ALL');
    expect(component.issueType()).toBe('ALL');
    expect(component.metricFocus()).toBe('DEFAULT');
    expect(component.reportGroupId()).toBeNull();
    expect(component.deepLinkSequenceNumber()).toBeNull();
    expect(component.page()).toBe(0);
  });

  it('renders the populated page', () => {
    start();
    answerLookups();
    queueRequest().flush(explorer([queueItem(), queueItem({ batchId: 'OK', status: 'SUCCESSFUL', totalIssues: 0, transformationFailureMismatch: false })]));
    http.expectOne('/api/v1/batches/1/BIN%201/1').flush(details());
    http.expectOne('/api/v1/report-configs/1/2/tv%2F1').flush(configDetails);
    fixture.detectChanges();
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('BIN 1');
    expect(text).toContain('OK');
  });
});
