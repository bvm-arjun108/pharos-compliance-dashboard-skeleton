import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { TransactionReportComponent } from '../../src/app/transaction-report.component';
import { RouteStub, RouterStub } from '../support/router-stubs';

const record = (overrides: Record<string, unknown> = {}) => ({
  recordKey: 'JOURNEY:1',
  identifier: '1',
  mtcn: 'M1',
  batchId: 'B1',
  source: 'JOURNEY',
  stage: 'TRANSFORMATION',
  status: 'SUCCESS',
  outcome: 'SUCCESS',
  comments: null,
  skipReason: null,
  exclusionReason: null,
  reportedBatchId: null,
  modifiedAt: '2026-09-01T00:00:00Z',
  processingComplete: true,
  ...overrides
});

const detail = (overrides: Record<string, unknown> = {}) => ({
  recordKey: 'JOURNEY:1',
  identifier: '1',
  mtcn: 'M1',
  batchId: 'B1',
  senderName: 'S',
  senderCity: null,
  senderCountry: null,
  senderPhone: null,
  senderDateOfBirth: null,
  senderIdType: null,
  senderIdNumber: null,
  receiverName: 'R',
  receiverCity: null,
  receiverCountry: null,
  receiverPhone: null,
  receiverDateOfBirth: null,
  receiverIdType: null,
  receiverIdNumber: null,
  currencyAmount: 1234.5,
  currencyCode: 'USD',
  transactionDate: null,
  sendDate: null,
  transactionSide: null,
  transactionStatus: null,
  transactionSubStatus: null,
  comments: null,
  skipReason: null,
  ruleId: null,
  exclusionReason: null,
  exclusionStrategy: null,
  reportingTimestamp: null,
  txnSource: null,
  activityType: null,
  galacticId: null,
  bucketId: null,
  attemptId: null,
  ruleHitsJson: null,
  ...overrides
});

const batchReport = (transactions: ReturnType<typeof record>[], matching = transactions.length) => ({
  context: {
    reportGroupId: 1,
    reportGroupName: 'RG 1',
    batchId: 'B1',
    sequenceNumber: 1,
    countryCode: 'US',
    countryName: 'United States',
    reportingPeriodFrom: '2026-09-01',
    reportingPeriodTo: '2026-09-30'
  },
  metric: 'ALL',
  metricLabel: 'Available transaction evidence',
  aggregateCount: matching,
  reportedAggregateCount: matching,
  aggregateCountMismatch: false,
  availableRecordCount: matching,
  matchingRecordCount: matching,
  evidenceLevel: 'RECORD_LEVEL',
  evidenceMessage: 'ok',
  transactions,
  search: '',
  source: 'ALL',
  outcome: 'ALL',
  status: 'ALL',
  sortDirection: 'DESC',
  page: 0,
  size: 25
});

const periodReport = (transactions: ReturnType<typeof record>[], matching = transactions.length) => ({
  context: {
    reportGroupId: null,
    reportGroupName: null,
    countryCode: 'ALL',
    countryName: 'All countries',
    fromDate: '2026-09-01',
    toDate: '2026-09-30',
    batchCount: 2
  },
  metricLabel: 'All transactions',
  aggregateCount: matching,
  availableRecordCount: matching,
  matchingRecordCount: matching,
  evidenceLevel: 'RECORD_LEVEL',
  evidenceMessage: 'ok',
  transactions,
  search: '',
  outcome: 'ALL',
  status: 'ALL',
  sortDirection: 'DESC',
  page: 0,
  size: 25
});

describe('TransactionReportComponent', () => {
  let fixture: ComponentFixture<TransactionReportComponent>;
  let component: TransactionReportComponent;
  let http: HttpTestingController;
  let route: RouteStub;
  let router: RouterStub;

  beforeEach(async () => {
    route = new RouteStub();
    router = new RouterStub();
    await TestBed.configureTestingModule({
      imports: [TransactionReportComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(TransactionReportComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const start = (query: Record<string, string>) => {
    route.setQuery(query);
    fixture.detectChanges();
  };
  const batchQuery = { reportGroupId: '1', batchId: 'B1', sequenceNumber: '1', metric: 'EXCLUDED', source: 'JOURNEY', status: 'FAILED', sortDirection: 'ASC', page: '1', search: ' x ' };
  const periodQuery = { fromDate: '2026-09-01', toDate: '2026-09-30', country: 'US', reportGroupId: '1', status: 'EXCLUDED', reason: ' r ', batchScopedExcluded: 'true', periodBatchId: ' B ', view: 'overview', origin: 'overview' };

  const loadBatch = (transactions = [record()], matching = transactions.length) => {
    start(batchQuery);
    http.expectOne(req => req.url === '/api/v1/transactions/report').flush(batchReport(transactions, matching));
    fixture.detectChanges();
  };
  const loadPeriod = (transactions = [record()], matching = transactions.length, query: Record<string, string> = periodQuery) => {
    start(query);
    http.expectOne('/api/v1/transactions/period-report/batches?' + new URLSearchParams({ fromDate: query['fromDate'], toDate: query['toDate'], country: query['country'] ?? 'ALL', ...(query['reportGroupId'] ? { reportGroupId: query['reportGroupId'] } : {}) }).toString()).flush(['B1', 'B2', 'X9']);
    http.expectOne(req => req.url === '/api/v1/transactions/period-report').flush(periodReport(transactions, matching));
    fixture.detectChanges();
  };

  it('does nothing without a usable scope', () => {
    start({});
    expect(component.hasContext()).toBe(false);
    expect(component.report()).toBeNull();
    expect(component.mode()).toBeNull();
  });

  it('loads a batch report with the route filters', () => {
    start(batchQuery);
    const request = http.expectOne(req => req.url === '/api/v1/transactions/report');
    expect(request.request.params.get('metric')).toBe('EXCLUDED');
    expect(request.request.params.get('search')).toBe('x');
    expect(request.request.params.get('sortDirection')).toBe('ASC');
    request.flush(batchReport([record()]));
    expect(component.mode()).toBe('BATCH');
    expect(component.report()?.context.kind).toBe('BATCH');
    expect(component.loading()).toBe(false);
  });

  it('reports an error when the batch report fails', () => {
    start(batchQuery);
    http.expectOne(req => req.url === '/api/v1/transactions/report').flush('x', { status: 500, statusText: 'err' });
    expect(component.error()).toContain('could not be loaded');
    expect(component.loading()).toBe(false);
  });

  it('loads a period report, the batch picker, and reads overview flags', () => {
    loadPeriod();
    expect(component.mode()).toBe('PERIOD');
    expect(component.report()?.context.kind).toBe('PERIOD');
    expect(component.overviewOnly()).toBe(true);
    expect(component.returnToTransactionView()).toBe(true);
    expect(component.batchScopedExcluded()).toBe(true);
    expect(component.reason()).toBe('r');
    expect(component.batchIdOptions()).toEqual(['B1', 'B2', 'X9']);
  });

  it('loads a period report without a report group or reason and skips an unchanged picker scope', () => {
    const query = { fromDate: '2026-09-01', toDate: '2026-09-30' };
    loadPeriod([record()], 1, query);
    route.setQuery({ ...query, page: '1' });
    http.expectOne(req => req.url === '/api/v1/transactions/period-report').flush(periodReport([]));
    expect(component.reportGroupId()).toBeNull();
  });

  it('reports errors for a failed period report and a failed picker', () => {
    start({ fromDate: '2026-09-01', toDate: '2026-09-30' });
    http.expectOne(req => req.url === '/api/v1/transactions/period-report/batches').flush('x', { status: 500, statusText: 'err' });
    http.expectOne(req => req.url === '/api/v1/transactions/period-report').flush('x', { status: 500, statusText: 'err' });
    expect(component.batchIdOptions()).toEqual([]);
    expect(component.error()).toContain('could not be loaded');
  });

  it('filters the batch picker as the user types', () => {
    jest.useFakeTimers();
    loadPeriod();
    component.batchIdFilter.set('');
    expect(component.filteredBatchIdOptions()).toHaveLength(3);
    component.setBatchIdFilter({ target: { value: ' b' } } as unknown as Event);
    expect(component.batchDropdownOpen()).toBe(true);
    expect(component.filteredBatchIdOptions()).toEqual(['B1', 'B2']);
    component.selectBatchId('B2');
    expect(component.batchIdFilter()).toBe('B2');
    expect(component.batchDropdownOpen()).toBe(false);
    component.openBatchDropdown();
    expect(component.batchDropdownOpen()).toBe(true);
    component.closeBatchDropdown();
    jest.advanceTimersByTime(150);
    jest.useRealTimers();
    expect(component.batchDropdownOpen()).toBe(false);
    component.setSearch({ target: { value: 'abc' } } as unknown as Event);
    component.setStatus({ target: { value: 'REPORTED' } } as unknown as Event);
    expect(component.search()).toBe('abc');
    expect(component.status()).toBe('REPORTED');
  });

  it('navigates for sorting, filtering and paging', () => {
    loadBatch([record()], 120);
    const report = component.report()!;
    component.toggleSortDirection();
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { sortDirection: 'DESC', page: 0 } }));
    component.sortDirection.set('DESC');
    component.toggleSortDirection();
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { sortDirection: 'ASC', page: 0 } }));

    const preventDefault = jest.fn();
    component.setSearch({ target: { value: '  ' } } as unknown as Event);
    component.applyFilters({ preventDefault } as unknown as SubmitEvent);
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: expect.objectContaining({ search: null, periodBatchId: null }) }));
    component.batchIdFilter.set('B');
    component.applyFilters({ preventDefault } as unknown as SubmitEvent);
    component.clearFilters();
    expect(component.batchIdFilter()).toBe('');

    router.navigate.mockClear();
    component.page.set(0);
    component.previousPage();
    expect(router.navigate).not.toHaveBeenCalled();
    component.page.set(2);
    component.previousPage();
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { page: 1 } }));
    component.page.set(0);
    component.nextPage(report);
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { page: 1 } }));
    router.navigate.mockClear();
    component.page.set(4);
    component.nextPage(report);
    expect(router.navigate).not.toHaveBeenCalled();
    component.goToPage(3);
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { page: 2 } }));
  });

  it('computes pagination', () => {
    loadBatch([record()], 500);
    const report = component.report()!;
    expect(component.totalPages(report)).toBe(20);
    component.page.set(0);
    expect(component.pageNumbers(report)).toEqual([1, 2, 3, '…', 20]);
    component.page.set(9);
    expect(component.pageNumbers(report)).toEqual([1, '…', 8, 9, 10, 11, 12, '…', 20]);
    component.page.set(19);
    expect(component.pageNumbers(report)).toEqual([1, '…', 18, 19, 20]);
    expect(component.pageRange(report)).toBe('476–476 of 500');
    expect(component.pageRange({ ...report, matchingRecordCount: 0 })).toBe('0 records');
    component.page.set(0);
    expect(component.totalPages({ ...report, matchingRecordCount: 0 })).toBe(1);
    expect(component.pageNumbers({ ...report, matchingRecordCount: 0 })).toEqual([1]);
  });

  it('navigates back according to where the report was reached from', () => {
    loadBatch();
    component.goBack();
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], expect.anything());
    component.openBatchView();

    route.setQuery({ fromDate: '2026-09-01', toDate: '2026-09-30', origin: 'overview' });
    http.expectOne(req => req.url === '/api/v1/transactions/period-report/batches').flush([]);
    http.expectOne(req => req.url === '/api/v1/transactions/period-report').flush(periodReport([]));
    component.goBack();
    expect(router.navigate).toHaveBeenLastCalledWith(['/transaction-view'], expect.anything());

    route.setQuery({ fromDate: '2026-09-01', toDate: '2026-09-30' });
    http.expectOne(req => req.url === '/api/v1/transactions/period-report').flush(periodReport([]));
    component.goBack();
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches']);
  });

  it('opens related batches and report configurations', () => {
    loadBatch();
    const event = { stopPropagation: jest.fn() } as unknown as Event;
    component.viewBatch(null, event);
    expect(router.navigate).not.toHaveBeenCalledWith(['/batches/explorer'], { queryParams: expect.objectContaining({ batchId: null }) });
    component.viewBatch('B7', event);
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], { queryParams: expect.objectContaining({ batchId: 'B7', reportGroupId: 1 }) });
    component.mode.set('PERIOD');
    component.viewBatch('B7', event);
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], { queryParams: { batchId: 'B7', fromDate: '2000-01-01', toDate: '2099-12-31' } });
    router.navigate.mockClear();
    component.viewReportConfig(null, event);
    expect(router.navigate).not.toHaveBeenCalled();
    component.viewReportConfig(3, event);
    expect(router.navigate).toHaveBeenCalledWith(['/report-config'], { queryParams: { reportGroupId: 3, status: 'ALL' } });
  });

  it('labels the context group', () => {
    const base = { kind: 'PERIOD', reportGroupId: null, reportGroupName: null, countryCode: 'ALL', countryName: 'All', fromDate: 'a', toDate: 'b', batchCount: 1 } as never;
    expect(component.contextGroupLabel(base)).toBe('All report groups');
    expect(component.contextGroupLabel({ ...(base as object), reportGroupId: 5, reportGroupName: 'Name' } as never)).toBe('Name');
    expect(component.contextGroupLabel({ ...(base as object), reportGroupId: 5 } as never)).toBe('Report group 5');
  });

  it('summarises a record for the list', () => {
    expect(component.recordDetail(record({ source: 'RULE_HIT', status: 'REPORTED' }) as never).primary).toBe('Reported');
    expect(component.recordDetail(record({ source: 'RULE_HIT', status: 'NOT_REPORTED' }) as never).primary).toBe('Not yet reported');
    expect(component.recordDetail(record({ processingComplete: false }) as never).primary).toBe('Processing incomplete');
    expect(component.recordDetail(record() as never).primary).toBe('No additional detail');
    expect(component.recordDetail(record({ comments: 'EXCLUDED_BECAUSE_SML' }) as never).primary).toBe('Excluded Because Sml');
    expect(component.recordDetail(record({ comments: 'free text here' }) as never).primary).toBe('free text here');
    expect(component.recordDetail(record({ exclusionReason: 'first', skipReason: 'second' }) as never).primary).toBe('first');
    const issues = JSON.stringify([{ Exception: 'Bad value', Path: 'amount', RuleSetName: 'AMOUNT_RULES', ErrorCode: 'E1' }, { message: 'two' }, 'plain']);
    const withIssues = component.recordDetail(record({ skipReason: issues }) as never);
    expect(withIssues.primary).toBe('Bad value');
    expect(withIssues.extras).toEqual(['Field: amount', 'Rule set: Amount Rules', 'Error code: E1', '+2 more issues']);
    const single = component.recordDetail(record({ skipReason: JSON.stringify([{ foo: 'bar' }]) }) as never);
    expect(single.primary).toBe('{"foo":"bar"}');
    expect(single.extras).toEqual([]);
    const one = component.recordDetail(record({ skipReason: JSON.stringify([{ exception: 'a' }, { Message: 'b' }]) }) as never);
    expect(one.extras).toEqual(['+1 more issue']);
    expect(component.recordDetail(record({ skipReason: '[]' }) as never).primary).toBe('[]');
    expect(component.recordDetail(record({ skipReason: '{"a":1}' }) as never).primary).toBe('{"a":1}');
  });

  it('summarises the skip reason of an expanded detail', () => {
    expect(component.skipReasonDetail(null).primary).toBe('Not available');
    expect(component.skipReasonDetail(detail() as never).primary).toBe('Not available');
    expect(component.skipReasonDetail(detail({ skipReason: 'SOME_CODE' }) as never).primary).toBe('Some Code');
    const issues = JSON.stringify([{ Exception: 'Boom', Path: 'p', RuleSetName: 'R_S', ErrorCode: 'C' }, { Exception: 'Two' }]);
    const parsed = component.skipReasonDetail(detail({ skipReason: issues }) as never);
    expect(parsed.primary).toBe('Boom');
    expect(parsed.extras).toEqual(['Field: p', 'Rule set: R S', 'Error code: C', '+1 more issue']);
    expect(component.skipReasonDetail(detail({ skipReason: JSON.stringify([{ a: 1 }, {}, {}]) }) as never).extras).toEqual(['+2 more issues']);
  });

  it('humanizes codes', () => {
    expect(component.humanize(null)).toBe('Not available');
    expect(component.humanize('')).toBe('Not available');
    expect(component.humanize('ALREADY_REPORTED(PRIOR)')).toBe('Already Reported (Prior)');
    expect(component.humanize('( )')).toBe('( )');
    expect(component.humanizeIfCode('ABC_1')).toBe('Abc 1');
    expect(component.humanizeIfCode('Some sentence.')).toBe('Some sentence.');
  });

  it('lists rule ids and rule hits', () => {
    expect(component.ruleHits(null)).toEqual([]);
    expect(component.ruleHits(detail() as never)).toEqual([]);
    expect(component.ruleHits(detail({ ruleHitsJson: '{bad' }) as never)).toEqual([]);
    expect(component.ruleHits(detail({ ruleHitsJson: '{"a":1}' }) as never)).toEqual([]);
    const hits = JSON.stringify([{ ruleId: 'R1' }, { ruleId: 'R1' }, { ruleId: null }, { ruleId: 'R2' }]);
    expect(component.ruleHits(detail({ ruleHitsJson: hits }) as never)).toHaveLength(4);
    expect(component.ruleIdsDisplay(detail({ ruleId: 'DIRECT' }) as never)).toBe('DIRECT');
    expect(component.ruleIdsDisplay(detail({ ruleHitsJson: hits }) as never)).toBe('R1, R2');
    expect(component.ruleIdsDisplay(detail() as never)).toBe('Not available');
    expect(component.ruleIdsDisplay(null)).toBe('Not available');
  });

  it('formats the currency', () => {
    expect(component.formatCurrency(null)).toBe('Not available');
    expect(component.formatCurrency(detail({ currencyAmount: null }) as never)).toBe('Not available');
    expect(component.formatCurrency(detail() as never)).toContain('USD');
    expect(component.formatCurrency(detail({ currencyCode: null }) as never)).not.toContain('USD');
  });

  it('expands a batch row, fetches and caches its detail', () => {
    loadBatch([record(), record({ recordKey: 'J:2', identifier: '2' })]);
    const row = component.report()!.transactions[0];
    component.toggleExpanded(row);
    const request = http.expectOne(req => req.url === '/api/v1/transactions/detail');
    expect(request.request.params.get('scope')).toBe('BATCH');
    expect(request.request.params.get('metric')).toBe('EXCLUDED');
    expect(request.request.params.get('reportGroupId')).toBe('1');
    expect(component.detailLoadingKey()).toBe('JOURNEY:1');
    component.loadDetail(row);
    http.expectNone(req => req.url === '/api/v1/transactions/detail');
    request.flush(detail());
    expect(component.detailFor(row)?.senderName).toBe('S');
    expect(component.detailLoadingKey()).toBeNull();

    component.toggleExpanded(row);
    expect(component.expandedRecordKey()).toBeNull();
    component.toggleExpanded(row);
    http.expectNone(req => req.url === '/api/v1/transactions/detail');
    expect(component.detailFor(component.report()!.transactions[1])).toBeNull();
  });

  it('fetches a period detail with the period scope', () => {
    loadPeriod();
    const row = component.report()!.transactions[0];
    component.toggleExpanded(row);
    const request = http.expectOne(req => req.url === '/api/v1/transactions/detail');
    expect(request.request.params.get('scope')).toBe('PERIOD');
    expect(request.request.params.get('batchScopedExcluded')).toBe('true');
    expect(request.request.params.get('batchIdFilter')).toBe('B');
    request.flush(detail());
  });

  it('surfaces detail errors', () => {
    loadBatch([record(), record({ recordKey: 'J:2', batchId: null })]);
    const [first, second] = component.report()!.transactions;
    component.toggleExpanded(second);
    expect(component.detailErrorKey()).toBe('J:2');

    component.toggleExpanded(first);
    http.expectOne(req => req.url === '/api/v1/transactions/detail').flush('x', { status: 500, statusText: 'err' });
    expect(component.detailErrorKey()).toBe('JOURNEY:1');

    component.reportGroupId.set(null);
    component.detailCache.set({});
    component.loadDetail(first);
    expect(component.detailErrorKey()).toBe('JOURNEY:1');
  });

  it('ignores detail responses that arrive after the list was reloaded', () => {
    loadBatch([record()]);
    const row = component.report()!.transactions[0];
    component.toggleExpanded(row);
    const stale = http.expectOne(req => req.url === '/api/v1/transactions/detail');
    route.setQuery({ ...batchQuery, page: '2' });
    http.expectOne(req => req.url === '/api/v1/transactions/report').flush(batchReport([record()]));
    stale.flush(detail());
    expect(component.detailFor(row)).toBeNull();

    component.toggleExpanded(row);
    const failing = http.expectOne(req => req.url === '/api/v1/transactions/detail');
    route.setQuery({ ...batchQuery, page: '3' });
    http.expectOne(req => req.url === '/api/v1/transactions/report').flush(batchReport([record()]));
    failing.flush('x', { status: 500, statusText: 'err' });
    expect(component.detailErrorKey()).toBeNull();
  });

  it('shares an in-flight detail request between repeated expansions', () => {
    loadBatch([record()]);
    const row = component.report()!.transactions[0];
    component.loadDetail(row);
    component.detailLoadingKey.set(null);
    component.loadDetail(row);
    expect(component.detailLoadingKey()).toBe('JOURNEY:1');
    http.expectOne(req => req.url === '/api/v1/transactions/detail').flush(detail());
  });

  it('keeps the loading marker when a different row finishes loading and errors only for the open row', () => {
    loadBatch([record(), record({ recordKey: 'J:2', identifier: '2' })]);
    const [first, second] = component.report()!.transactions;
    component.loadDetail(first);
    component.loadDetail(second);
    const requests = http.match(req => req.url === '/api/v1/transactions/detail');
    expect(component.detailLoadingKey()).toBe('J:2');
    requests[0].flush(detail());
    expect(component.detailLoadingKey()).toBe('J:2');
    requests[1].flush('x', { status: 500, statusText: 'err' });
    expect(component.detailErrorKey()).toBeNull();
  });

  it('renders the populated report', () => {
    loadBatch([record({ comments: 'EXCLUDED_BECAUSE_SML' }), record({ recordKey: 'R:1', source: 'RULE_HIT', status: 'REPORTED' })], 2);
    component.toggleExpanded(component.report()!.transactions[0]);
    http.expectOne(req => req.url === '/api/v1/transactions/detail').flush(detail({ ruleHitsJson: JSON.stringify([{ ruleId: 'R1', isReported: true, reportingTimestamp: null, bucketId: 1, attemptId: 2 }]) }));
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Available transaction evidence');
  });

  it('renders the period report', () => {
    loadPeriod([record(), record({ recordKey: 'X', outcome: 'EXCLUDED', exclusionReason: 'ALREADY_REPORTED', reportedBatchId: 'B0' })], 2);
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('All transactions');
  });

  it('ignores invalid route values', () => {
    start({ reportGroupId: 'x', sequenceNumber: '-1', batchId: 'B', metric: 'nope', source: 'nope', status: 'nope', page: 'x', country: ' ' });
    expect(component.metric()).toBe('ALL');
    expect(component.source()).toBe('ALL');
    expect(component.status()).toBe('ALL');
    expect(component.page()).toBe(0);
    expect(component.country()).toBe('ALL');
    expect(component.mode()).toBeNull();
  });
});
