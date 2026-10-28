import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { DashboardFilterStateService } from '../../src/app/dashboard-filter-state.service';
import { TransactionOverviewComponent } from '../../src/app/transaction-overview.component';
import { RouteStub, RouterStub } from '../support/router-stubs';

const trend = (start: string, reported: number | null, excluded: number | null) => ({
  periodStart: start,
  periodEnd: start,
  totalReportedTransactions: reported,
  totalExcludedTransactions: excluded
});

const dashboard = (overrides: Record<string, unknown> = {}) => ({
  transactionOverview: { selected: 1000, expected: 900, excluded: 100, notReported: 9 },
  topExclusionReasons: [
    { reason: 'ALREADY_REPORTED', count: 60 },
    { reason: 'Free text reason', count: 40 }
  ],
  notReportedReasons: [{ reason: 'NOT_YET_SENT', count: 9 }],
  trendGranularity: 'DAILY',
  batchHealthTrend: [trend('2026-09-01', 10, 1), trend('2026-09-02', 0, 0), trend('2026-09-03', null, null)],
  fromDate: '2026-09-01',
  toDate: '2026-09-07',
  ...overrides
});

describe('TransactionOverviewComponent', () => {
  let fixture: ComponentFixture<TransactionOverviewComponent>;
  let component: TransactionOverviewComponent;
  let http: HttpTestingController;
  let route: RouteStub;
  let router: RouterStub;

  beforeEach(async () => {
    route = new RouteStub();
    router = new RouterStub();
    await TestBed.configureTestingModule({
      imports: [TransactionOverviewComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router }
      ]
    }).compileComponents();
  });

  const create = (query: Record<string, string> = {}) => {
    fixture = TestBed.createComponent(TransactionOverviewComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
    route.setQuery(query);
    route.snapshot = { ...route.snapshot };
    fixture.detectChanges();
  };
  const answerLookups = () => {
    http.expectOne('/api/v1/batches/filter-options').flush({ countries: [{ code: 'US', name: 'United States' }] });
    http.expectOne('/api/v1/report-configs/report-groups').flush([
      { reportGroupId: 1, reportGroupName: 'RG 1', countryCode: 'US' },
      { reportGroupId: 2, reportGroupName: 'RG 2', countryCode: 'CA' }
    ]);
  };
  const dashboardRequest = () => http.expectOne(req => req.url === '/dashboardDetails/transaction-view');
  const start = (query: Record<string, string> = { country: 'US' }, response: unknown = dashboard()) => {
    create(query);
    answerLookups();
    dashboardRequest().flush(response);
    fixture.detectChanges();
  };

  afterEach(() => http.verify());

  it('does not request the dashboard until a country is chosen', () => {
    create();
    answerLookups();
    expect(component.countryRequired()).toBe(true);
    expect(component.dashboardDetails()).toBeNull();
    expect(component.dashboardError()).toBeNull();
  });

  it('loads the dashboard for a country, normalising missing trend counts', () => {
    start({ country: 'US', reportGroupId: '1' });
    expect(component.dashboardDetails()?.batchHealthTrend[2].totalReportedTransactions).toBe(0);
    expect(component.countryRequired()).toBe(false);
  });

  it('passes the report group to the API', () => {
    create({ country: 'US', reportGroupId: '1' });
    answerLookups();
    const request = dashboardRequest();
    expect(request.request.params.get('reportGroupId')).toBe('1');
    expect(request.request.params.get('country')).toBe('US');
    request.flush(dashboard());
  });

  it('tolerates failing lookups and a failing dashboard', () => {
    create({ country: 'US' });
    http.expectOne('/api/v1/batches/filter-options').flush('x', { status: 500, statusText: 'err' });
    http.expectOne('/api/v1/report-configs/report-groups').flush('x', { status: 500, statusText: 'err' });
    dashboardRequest().flush('x', { status: 500, statusText: 'err' });
    expect(component.countryOptions()).toEqual([]);
    expect(component.reportGroupOptions()).toEqual([]);
    expect(component.dashboardError()).toContain('could not be loaded');
  });

  it('restores deep-linked and remembered filters', () => {
    start({ country: 'US', fromDate: '2026-09-01', toDate: '2026-09-30', period: 'LAST_30_DAYS' });
    expect(component.reportPeriod()).toBe('LAST_30_DAYS');
    expect(component.filtersApplied()).toBe(true);
  });

  it('treats a deep link without a period as custom and falls back to remembered state', () => {
    start({ country: 'US', fromDate: '2026-09-01', toDate: '2026-09-30' });
    expect(component.reportPeriod()).toBe('CUSTOM');
  });

  it('applies and resets filters, persisting them', () => {
    start();
    component.setReportPeriod({ target: { value: 'LAST_30_DAYS' } } as unknown as Event);
    expect(component.filtersApplied()).toBe(false);
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    dashboardRequest().flush(dashboard());
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ replaceUrl: true }));
    expect(TestBed.inject(DashboardFilterStateService).get()?.country).toBe('US');

    component.setReportGroupValue('1');
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    expect(dashboardRequest().request.params.get('reportGroupId')).toBe('1');
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: expect.objectContaining({ reportGroupId: '1' }) }));

    component.resetFilters();
    expect(component.country()).toBe('ALL');
    expect(component.reportPeriod()).toBe('LAST_7_DAYS');
    http.expectNone(req => req.url === '/dashboardDetails/transaction-view');
  });

  it('keeps previously stored batch id when persisting', () => {
    TestBed.inject(DashboardFilterStateService).set({ batchId: 'KEEP', country: 'US', reportGroupId: 'ALL', reportPeriod: 'LAST_7_DAYS', startDate: '', endDate: '' });
    start({});
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    dashboardRequest().flush(dashboard());
    expect(TestBed.inject(DashboardFilterStateService).get()?.batchId).toBe('KEEP');
  });

  it('requires both custom dates', () => {
    start();
    component.setReportPeriod({ target: { value: 'CUSTOM' } } as unknown as Event);
    component.setStartDate({ target: { value: '2026-09-01' } } as unknown as Event);
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    expect(component.dashboardError()).toBe('Select both custom dates.');
    expect(component.dashboardDetails()).toBeNull();

    component.openExcludedTransactionsExplorer();
    component.openNotReportedTransactionsExplorer();
    expect(router.navigate).not.toHaveBeenCalledWith(['/transactions'], expect.anything());

    component.setSearchQuery({ target: { value: '123' } } as unknown as Event);
    component.runSearch({ preventDefault: jest.fn() } as unknown as Event);
    expect(component.searchError()).toBe('Select both custom dates.');
    http.expectNone('/api/v1/transactions/search');

    component.setEndDate({ target: { value: '2026-09-30' } } as unknown as Event);
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    dashboardRequest().flush(dashboard());
  });

  it('keeps the country and report group consistent', () => {
    start();
    component.setReportGroupValue('2');
    expect(component.country()).toBe('CA');
    component.setReportGroupValue('99');
    expect(component.country()).toBe('CA');
    component.setCountryValue('US');
    expect(component.reportGroupId()).toBe('ALL');
    component.setReportGroupValue('1');
    component.setCountryValue('US');
    expect(component.reportGroupId()).toBe('1');
    component.setCountryValue('ALL');
    expect(component.filteredReportGroupOptions()).toHaveLength(2);
    component.setCountryValue('CA');
    expect(component.filteredReportGroupOptions().map(option => option.reportGroupId)).toEqual([2]);
  });

  it('opens the drill-through reports', () => {
    start({ country: 'US', reportGroupId: '1', fromDate: '2026-09-01', toDate: '2026-09-07' });
    component.openExcludedTransactionsExplorer('ALREADY_REPORTED');
    expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], { queryParams: expect.objectContaining({ status: 'EXCLUDED', reason: 'ALREADY_REPORTED', view: 'overview', reportGroupId: 1 }) });
    component.openExcludedTransactionsExplorer();
    expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], { queryParams: expect.objectContaining({ reason: null }) });
    component.openNotReportedTransactionsExplorer('X');
    expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], { queryParams: expect.objectContaining({ status: 'NOT_REPORTED', reason: 'X' }) });
    component.openNotReportedTransactionsExplorer();
    component.reportGroupId.set('ALL');
    component.openPeriodTransactionExplorer(trend('2026-09-01', 1, 1) as never, 'REPORTED');
    expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], { queryParams: expect.objectContaining({ status: 'REPORTED', reportGroupId: null, origin: 'overview' }) });
    component.openPeriodTransactionExplorer(trend('2026-09-01', 1, 1) as never, 'EXCLUDED');
    expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], { queryParams: expect.objectContaining({ batchScopedExcluded: true }) });
    router.navigate.mockClear();
    component.openReportedTrendPoint(trend('2026-09-01', 0, 1) as never);
    component.openExcludedTrendPoint(trend('2026-09-01', 1, 0) as never);
    expect(router.navigate).not.toHaveBeenCalled();
    component.openReportedTrendPoint(trend('2026-09-01', 1, 0) as never);
    component.openExcludedTrendPoint(trend('2026-09-01', 0, 1) as never);
    expect(router.navigate).toHaveBeenCalledTimes(2);
  });

  describe('search', () => {
    const searchFor = (value: string, setup?: () => void) => {
      start({ country: 'US', fromDate: '2026-09-01', toDate: '2026-09-07', reportGroupId: '1' });
      setup?.();
      component.setSearchQuery({ target: { value } } as unknown as Event);
    };
    const run = () => component.runSearch({ preventDefault: jest.fn() } as unknown as Event);
    const request = () => http.expectOne(req => req.url === '/api/v1/transactions/search');

    it('ignores a blank query', () => {
      searchFor('   ');
      run();
      http.expectNone(req => req.url === '/api/v1/transactions/search');
    });

    it('sends the whole scope and redirects on the first MTCN', () => {
      searchFor(' 123 ');
      component.setSearchField({ target: { value: 'EXTERNAL_TXN_ID' } } as unknown as Event);
      expect(component.searchPlaceholder()).toBe('External Txn ID');
      run();
      expect(component.searchLoading()).toBe(true);
      run();
      const sent = request();
      expect(sent.request.params.get('field')).toBe('EXTERNAL_TXN_ID');
      expect(sent.request.params.get('query')).toBe('123');
      expect(sent.request.params.get('country')).toBe('US');
      expect(sent.request.params.get('reportGroupId')).toBe('1');
      sent.flush({ query: '123', results: [{ mtcn: null, identifier: '9' }, { mtcn: 'M1', identifier: '9' }] });
      expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], { queryParams: expect.objectContaining({ search: 'M1', country: 'US', reportGroupId: '1' }) });
      expect(component.searchLoading()).toBe(false);
    });

    it('falls back to the identifier when no match has an MTCN', () => {
      searchFor('123', () => {
        component.country.set('ALL');
        component.reportGroupId.set('ALL');
      });
      expect(component.searchPlaceholder()).toBe('MTCN');
      run();
      const sent = request();
      expect(sent.request.params.has('country')).toBe(false);
      expect(sent.request.params.has('reportGroupId')).toBe(false);
      sent.flush({ query: '123', results: [{ mtcn: null, identifier: '77_P' }] });
      expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], { queryParams: expect.objectContaining({ search: '77_P' }) });
    });

    it('reports no evidence and failures', () => {
      searchFor('123');
      run();
      request().flush({ query: '123', results: [{ mtcn: null, identifier: null }] });
      expect(component.searchError()).toContain('No evidence found for "123"');
      run();
      request().flush('x', { status: 500, statusText: 'err' });
      expect(component.searchError()).toContain('Could not complete the search');
      expect(component.searchLoading()).toBe(false);
    });
  });

  it('computes the gauge geometry and colours', () => {
    start();
    const overview = dashboard().transactionOverview;
    expect(component.exclusionRatePercent(overview)).toBe(10);
    expect(component.exclusionRatePercent({ ...overview, selected: 0 })).toBe(0);
    expect(component.notReportedRatePercent(overview)).toBe(1);
    expect(component.notReportedRatePercent({ ...overview, expected: 0 })).toBe(0);
    expect(component.gaugeNeedleAngle(-5)).toBe(0);
    expect(component.gaugeNeedleAngle(0.5)).toBe(22.5);
    expect(component.gaugeNeedleAngle(3)).toBe(67.5);
    expect(component.gaugeNeedleAngle(7.5)).toBe(112.5);
    expect(component.gaugeNeedleAngle(15)).toBe(157.5);
    expect(component.gaugeNeedleAngle(1000)).toBe(180);
    expect(component.gaugeNeedleX(0)).toBeCloseTo(42, 5);
    expect(component.gaugeNeedleY(0)).toBeCloseTo(110, 5);
    expect(component.gaugeZoneColor(0.5)).toBe('#547563');
    expect(component.gaugeZoneColor(2)).toBe('#8a7d3f');
    expect(component.gaugeZoneColor(7)).toBe('#a05f2e');
    expect(component.gaugeZoneColor(50)).toBe('#bd343e');
    expect(component.formatGaugeRate(1.234)).toBe('1.23%');
  });

  it('computes the breakdown helpers', () => {
    start();
    const periods = dashboard().batchHealthTrend as never[];
    expect(component.trendReportedTransactionsMaximum(periods)).toBe(10);
    expect(component.trendReportedTransactionsMaximum([])).toBe(1);
    expect(component.exclusionReasonSharePercent(25, 100)).toBe(25);
    expect(component.exclusionReasonSharePercent(1, 0)).toBe(0);
    expect(component.exclusionReasonColor(0)).toBe('#2a78d6');
    expect(component.exclusionReasonColor(8)).toBe('#2a78d6');
    expect(component.notReportedReasonColor(1)).toBe('#b8ab72');
    expect(component.notReportedReasonColor(5)).toBe('#b8ab72');
    expect(component.humanizeReason('ALREADY_REPORTED(PRIOR)')).toBe('Already Reported (Prior)');
    expect(component.humanizeReason('( )')).toBe('( )');
    expect(component.humanizeReason('Free text')).toBe('Free text');
  });

  it('colours the heatmaps', () => {
    start();
    expect(component.reportedHeatmapCellColor(5, 0)).toBe('#efeee9');
    expect(component.reportedHeatmapCellColor(0, 10)).toBe('#f9f5f5');
    expect(component.reportedHeatmapCellColor(5, 10)).toContain('rgba(111, 146, 127');
    expect(component.reportedHeatmapTextColor(9, 10)).toBe('#ffffff');
    expect(component.reportedHeatmapTextColor(0, 10)).toBe('#aaa9a1');
    expect(component.reportedHeatmapTextColor(1, 10)).toBe('#2f4a3b');
    expect(component.reportedHeatmapTextColor(1, 0)).toBe('#2f4a3b');
    expect(component.excludedHeatmapCellColor(1, 0)).toBe('#efeee9');
    expect(component.excludedHeatmapCellColor(0, 10)).toBe('#f9f5f5');
    expect(component.excludedHeatmapCellColor(1, 100)).toContain('rgba(240, 180, 0');
    expect(component.excludedHeatmapTextColor(50, 100)).toBe('#3a2e00');
    expect(component.excludedHeatmapTextColor(0, 100)).toBe('#aaa9a1');
    expect(component.excludedHeatmapTextColor(1, 100)).toBe('#6b5400');
    expect(component.excludedHeatmapTextColor(1, 0)).toBe('#6b5400');
  });

  it('builds titles, labels and widths', () => {
    start();
    expect(component.reportedTransactionsCellTitle(trend('a', 3, 1) as never)).toBe('Reported transactions: 3 from a to a');
    expect(component.excludedTransactionsCellTitle(trend('a', 3, 1) as never)).toContain('1 of 4 (25.0%)');
    expect(component.excludedTransactionsCellTitle(trend('a', 0, 0) as never)).toContain('0 of 0 (0.0%)');
    expect(component.excludedTransactionsTrendTitle(undefined)).toBe('Transaction Totals Trend');
    expect(component.excludedTransactionsTrendTitle('WEEKLY')).toBe('Weekly Transaction Totals');
    expect(component.reportedTransactionsTrendTitle(undefined)).toBe('Reported Transactions Trend');
    expect(component.reportedTransactionsTrendTitle('DAILY')).toBe('Daily Reported Transactions');
    expect(component.excludedLineTrendTitle(undefined)).toBe('Excluded Transactions Trend');
    expect(component.excludedLineTrendTitle('MONTHLY')).toBe('Monthly Excluded Transactions');
    expect(component.trendDateFormat('MONTHLY')).toBe('MMM');
    expect(component.trendDateFormat('DAILY')).toBe('MMM d');
    expect(component.heatmapMinimumWidth(2, 'DAILY')).toBe(760);
    expect(component.heatmapMinimumWidth(30, 'DAILY')).toBe(1440);
    expect(component.heatmapMinimumWidth(30, 'WEEKLY')).toBe(2100);
    expect(component.heatmapMinimumWidth(30, 'MONTHLY')).toBe(2520);
    expect(component.trendChartWidth(1, 'DAILY')).toBe(680);
    expect(component.trendChartWidth(30, 'DAILY')).toBe(36 + 26 + 30 * 42);
    expect(component.trendChartWidth(30, 'WEEKLY')).toBe(36 + 26 + 30 * 64);
    expect(component.trendChartWidth(30, 'MONTHLY')).toBe(36 + 26 + 30 * 78);
  });

  it('draws the trend lines', () => {
    start();
    const periods = [trend('a', 10, 4), trend('b', 5, 0), trend('c', 0, 2)] as never[];
    const reportedPoints = component.reportedTrendPoints(periods, 400);
    expect(reportedPoints).toHaveLength(3);
    expect(component.reportedTrendLinePath(periods, 400).startsWith('M')).toBe(true);
    expect(component.reportedTrendAreaPath(periods, 400).endsWith('Z')).toBe(true);
    expect(component.reportedTrendAreaPath([], 400)).toBe('');
    expect(component.reportedTrendGridlines(periods).map(line => line.value)).toEqual([0, 5, 10]);
    expect(component.reportedTrendGridlines([trend('a', 0, 0)] as never[])).toHaveLength(1);

    expect(component.excludedTrendPoints(periods, 400)).toHaveLength(3);
    expect(component.excludedTrendLinePath(periods, 400)).toContain('L');
    expect(component.excludedTrendAreaPath(periods, 400).endsWith('Z')).toBe(true);
    expect(component.excludedTrendAreaPath([], 400)).toBe('');
    expect(component.excludedTrendGridlines(periods).map(line => line.value)).toEqual([0, 2, 4]);
    expect(component.excludedTrendGridlines([trend('a', 0, 0)] as never[])).toHaveLength(1);

    const single = component.reportedTrendPoints([trend('a', 1, 1)] as never[], 400);
    expect(single[0].x).toBeCloseTo(36 + (400 - 36 - 26) / 2, 5);
  });

  it('renders the dashboard in every granularity', () => {
    start({ country: 'US' }, dashboard({ trendGranularity: 'WEEKLY' }));
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('Find a transaction');
  });

  it('measures the trend card with a ResizeObserver when one exists', () => {
    const observe = jest.fn();
    const disconnect = jest.fn();
    let callback: ((entries: unknown[]) => void) | undefined;
    (globalThis as unknown as { ResizeObserver: unknown }).ResizeObserver = class {
      constructor(cb: (entries: unknown[]) => void) {
        callback = cb;
      }
      observe = observe;
      disconnect = disconnect;
    };
    try {
      start({ country: 'US' });
      fixture.detectChanges();
      if (observe.mock.calls.length > 0) {
        callback?.([{ contentRect: { width: 912.4 } }]);
        expect(component.trendChartContainerWidth()).toBe(912);
        callback?.([{ contentRect: { width: 0 } }]);
        callback?.([]);
        expect(component.trendChartContainerWidth()).toBe(912);
      }
      fixture.destroy();
    } finally {
      delete (globalThis as unknown as { ResizeObserver?: unknown }).ResizeObserver;
    }
  });
});
