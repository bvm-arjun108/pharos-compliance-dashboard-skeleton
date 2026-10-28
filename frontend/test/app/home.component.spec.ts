import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { DashboardFilterStateService } from '../../src/app/dashboard-filter-state.service';
import { HomeComponent } from '../../src/app/home.component';
import { RouteStub, RouterStub } from '../support/router-stubs';

const group = (id: number, overrides: Record<string, unknown> = {}) => ({
  reportGroupId: id,
  reportGroupName: `RG ${id}`,
  batchesRan: 10,
  successfulBatches: 8,
  batchesNeedingAttention: 2,
  transformationFailureBatches: 1,
  missingAttemptBatches: 1,
  activityMissingBatches: 0,
  totalReportedTransactions: 100,
  totalExcludedTransactions: 5,
  ...overrides
});

const dashboard = (overrides: Record<string, unknown> = {}) => ({
  batchesRan: 10,
  successfulBatches: 8,
  batchesNeedingAttention: 2,
  transformationFailureBatches: 1,
  missingAttemptBatches: 1,
  activityMissingBatches: 0,
  duplicateTransactionBatches: 1,
  exclusionBatches: 2,
  simulatedTransactionBatches: 3,
  softDedupBatches: 4,
  trendGranularity: 'DAILY',
  batchHealthTrend: [
    { periodStart: '2026-09-01', periodEnd: '2026-09-01', batchesRan: 5, successfulBatches: 4, batchesNeedingAttention: 1 },
    { periodStart: '2026-09-02', periodEnd: '2026-09-02', batchesRan: 0, successfulBatches: 0, batchesNeedingAttention: 0 }
  ],
  reportGroupsRequiringAttention: [group(1), group(2)],
  fromDate: '2026-09-01',
  toDate: '2026-09-07',
  ...overrides
});

describe('HomeComponent', () => {
  let fixture: ComponentFixture<HomeComponent>;
  let component: HomeComponent;
  let http: HttpTestingController;
  let route: RouteStub;
  let router: RouterStub;

  beforeEach(async () => {
    route = new RouteStub();
    router = new RouterStub();
    await TestBed.configureTestingModule({
      imports: [HomeComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router }
      ]
    }).compileComponents();
  });

  const create = () => {
    fixture = TestBed.createComponent(HomeComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  };
  const dashboardRequest = () => http.expectOne(req => req.url === '/dashboardDetails/batch-view');
  const answerLookups = () => {
    http.expectOne('/api/v1/batches/filter-options').flush({ countries: [{ code: 'US', name: 'United States' }] });
    http.expectOne('/api/v1/report-configs/report-groups').flush([
      { reportGroupId: 1, reportGroupName: 'RG 1', countryCode: 'US' },
      { reportGroupId: 2, reportGroupName: 'RG 2', countryCode: 'CA' }
    ]);
  };
  const start = (query: Record<string, string> = {}, response: unknown = dashboard()) => {
    create();
    route.setQuery(query);
    fixture.detectChanges();
    answerLookups();
    dashboardRequest().flush(response);
    fixture.detectChanges();
  };

  afterEach(() => http.verify());

  it('loads the default last-7-days dashboard', () => {
    create();
    fixture.detectChanges();
    answerLookups();
    const request = dashboardRequest();
    expect(request.request.params.get('batchId')).toBe('');
    expect(request.request.params.get('reportGroupId')).toBeNull();
    request.flush(dashboard());
    expect(component.dashboardDetails()?.batchesRan).toBe(10);
    expect(component.dashboardLoading()).toBe(false);
    expect(component.countryOptions()).toHaveLength(1);
    expect(component.reportGroupOptions()).toHaveLength(2);
  });

  it('tolerates failing lookups and a failing dashboard', () => {
    create();
    fixture.detectChanges();
    http.expectOne('/api/v1/batches/filter-options').flush('x', { status: 500, statusText: 'err' });
    http.expectOne('/api/v1/report-configs/report-groups').flush('x', { status: 500, statusText: 'err' });
    dashboardRequest().flush('x', { status: 500, statusText: 'err' });
    expect(component.countryOptions()).toEqual([]);
    expect(component.reportGroupOptions()).toEqual([]);
    expect(component.dashboardError()).toContain('could not be loaded');
    expect(component.dashboardDetails()).toBeNull();
  });

  it('restores a deep-linked custom period and filters', () => {
    start({ fromDate: '2026-09-01', toDate: '2026-09-30', batchId: 'B1', country: 'US', reportGroupId: '1', period: 'LAST_30_DAYS' });
    expect(component.reportPeriod()).toBe('LAST_30_DAYS');
    expect(component.startDate()).toBe('2026-09-01');
    expect(component.filtersApplied()).toBe(true);
    expect(component.reportGroupId()).toBe('1');
  });

  it('treats a deep link without a period parameter as custom', () => {
    start({ fromDate: '2026-09-01', toDate: '2026-09-30' });
    expect(component.reportPeriod()).toBe('CUSTOM');
  });

  it('restores filters remembered earlier in the session', () => {
    TestBed.inject(DashboardFilterStateService).set({
      batchId: 'R', country: 'CA', reportGroupId: '2', reportPeriod: 'CUSTOM', startDate: '2026-08-01', endDate: '2026-08-31'
    });
    start();
    expect(component.batchId()).toBe('R');
    expect(component.country()).toBe('CA');
    expect(component.startDate()).toBe('2026-08-01');
    expect(component.filtersApplied()).toBe(true);
  });

  it('applies and resets filters, persisting them', () => {
    start();
    component.setBatchId({ target: { value: ' B9 ' } } as unknown as Event);
    component.setReportPeriod({ target: { value: 'LAST_30_DAYS' } } as unknown as Event);
    expect(component.filtersApplied()).toBe(false);
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    const applied = dashboardRequest();
    expect(applied.request.params.get('batchId')).toBe('B9');
    applied.flush(dashboard());
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ replaceUrl: true }));
    expect(TestBed.inject(DashboardFilterStateService).get()?.batchId).toBe('B9');

    component.setReportGroupValue('1');
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    expect(dashboardRequest().request.params.get('reportGroupId')).toBe('1');

    component.resetFilters();
    expect(component.reportPeriod()).toBe('LAST_7_DAYS');
    expect(component.reportGroupId()).toBe('ALL');
    dashboardRequest().flush(dashboard());
  });

  it('requires both custom dates', () => {
    start();
    component.setReportPeriod({ target: { value: 'CUSTOM' } } as unknown as Event);
    component.setStartDate({ target: { value: '2026-09-01' } } as unknown as Event);
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    http.expectNone(req => req.url === '/dashboardDetails/batch-view');
    expect(component.dashboardError()).toBe('Select both custom dates.');
    expect(component.dashboardDetails()).toBeNull();

    component.openBatchExplorer('ATTENTION');
    component.openReportGroupExcludedTransactions(group(1) as never, { stopPropagation: jest.fn() } as unknown as Event);
    expect(router.navigate).not.toHaveBeenCalledWith(['/batches/explorer'], expect.anything());
    expect(router.navigate).not.toHaveBeenCalledWith(['/transactions'], expect.anything());

    component.setEndDate({ target: { value: '2026-09-30' } } as unknown as Event);
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    dashboardRequest().flush(dashboard());
    expect(component.dashboardError()).toBeNull();
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
    expect(component.attentionTableScoped()).toBe(true);
  });

  it('navigates to the batch explorer and transactions', () => {
    start({ fromDate: '2026-09-01', toDate: '2026-09-07', batchId: ' b ', country: 'US', reportGroupId: '1' });
    component.openBatchExplorer('ATTENTION', 'TRANSFORMATION');
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], { queryParams: expect.objectContaining({ status: 'ATTENTION', issueType: 'TRANSFORMATION', reportGroupId: 1, batchId: 'b', metricFocus: 'DEFAULT' }) });
    component.reportGroupId.set('ALL');
    component.openBatchExplorer('ALL');
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], { queryParams: expect.objectContaining({ reportGroupId: null }) });
    component.openReportGroupExplorer(group(7) as never);
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], { queryParams: expect.objectContaining({ reportGroupId: 7, status: 'ALL' }) });
    component.openReportGroupExplorer(group(7) as never, 'ATTENTION', 'EXCLUSION', 'EXCLUDED');
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], { queryParams: expect.objectContaining({ metricFocus: 'EXCLUDED' }) });

    const event = { stopPropagation: jest.fn() } as unknown as Event;
    component.openReportGroupExcludedTransactions(group(7) as never, event);
    expect(event.stopPropagation).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenLastCalledWith(['/transactions'], { queryParams: expect.objectContaining({ reportGroupId: 7, status: 'EXCLUDED', batchScopedExcluded: true }) });

    const trend = dashboard().batchHealthTrend[0];
    component.openPeriodExplorer(trend as never);
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], { queryParams: expect.objectContaining({ fromDate: '2026-09-01', toDate: '2026-09-01', status: 'ALL' }) });
    component.openPeriodExplorer(trend as never, 'SUCCESSFUL', 'SIMULATED');
    expect(router.navigate).toHaveBeenLastCalledWith(['/batches/explorer'], { queryParams: expect.objectContaining({ status: 'SUCCESSFUL', issueType: 'SIMULATED' }) });
  });

  it('sorts and pages the report groups', () => {
    start();
    const groups = [group(1, { reportGroupName: 'Zed', batchesNeedingAttention: 1 }), group(2, { reportGroupName: null, batchesNeedingAttention: 9 }), group(3, { reportGroupName: 'Alpha', batchesNeedingAttention: 5 })] as never[];
    expect(component.sortedReportGroups(groups).map(g => (g as { reportGroupId: number }).reportGroupId)).toEqual([2, 3, 1]);
    component.setAttentionSort('batchesNeedingAttention');
    expect(component.attentionSortDirection()).toBe('asc');
    expect(component.sortedReportGroups(groups).map(g => (g as { reportGroupId: number }).reportGroupId)).toEqual([1, 3, 2]);
    component.setAttentionSort('name');
    expect(component.attentionSortDirection()).toBe('asc');
    expect(component.sortedReportGroups(groups).map(g => (g as { reportGroupId: number }).reportGroupId)).toEqual([3, 2, 1]);
    component.setAttentionSort('name');
    expect(component.attentionSortDirection()).toBe('desc');
    component.setAttentionSort('totalExcludedTransactions');
    expect(component.attentionSortDirection()).toBe('desc');
    expect(component.attentionSortIndicator('name')).toBe('');
    expect(component.attentionSortIndicator('totalExcludedTransactions')).toBe(' ↓');
    component.setAttentionSort('totalExcludedTransactions');
    expect(component.attentionSortIndicator('totalExcludedTransactions')).toBe(' ↑');
    expect(component.attentionAriaSort('name')).toBe('none');
    expect(component.attentionAriaSort('totalExcludedTransactions')).toBe('ascending');
    component.setAttentionSort('totalExcludedTransactions');
    expect(component.attentionAriaSort('totalExcludedTransactions')).toBe('descending');

    const many = Array.from({ length: 25 }, (_, i) => group(i + 1)) as never[];
    expect(component.pagedReportGroups(many)).toHaveLength(10);
    expect(component.attentionTotalPages(25)).toBe(3);
    expect(component.attentionTotalPages(0)).toBe(1);
    expect(component.attentionPageRange(0)).toBe('0 of 0');
    expect(component.attentionPageRange(25)).toBe('1–10 of 25');
    component.nextAttentionPage(25);
    component.nextAttentionPage(25);
    component.nextAttentionPage(25);
    expect(component.attentionPage()).toBe(2);
    expect(component.attentionPageRange(25)).toBe('21–25 of 25');
    component.previousAttentionPage();
    component.previousAttentionPage();
    component.previousAttentionPage();
    expect(component.attentionPage()).toBe(0);
    component.goToAttentionPage(3);
    expect(component.attentionPage()).toBe(2);
    component.goToAttentionPage(1);
    expect(component.attentionPageNumbers(250)).toEqual([1, 2, 3, '…', 25]);
    component.goToAttentionPage(13);
    expect(component.attentionPageNumbers(250)).toEqual([1, '…', 11, 12, 13, 14, 15, '…', 25]);
    component.goToAttentionPage(1);
    expect(component.attentionPageNumbers(5)).toEqual([1]);
  });

  it('derives the headline numbers and trend geometry', () => {
    start();
    const d = dashboard() as never;
    expect(component.overallAttentionRate(d)).toBe(20);
    expect(component.overallAttentionRate(dashboard({ batchesRan: 0 }) as never)).toBe(0);
    expect(component.issueBreakdownSum(d)).toBe(2);
    expect(component.notNeedingAttentionBreakdownSum(d)).toBe(10);
    expect(component.trendTitle('WEEKLY')).toBe('Weekly Batch Health');
    expect(component.trendDescription('MONTHLY')).toContain('monthly');
    expect(component.trendDateFormat('MONTHLY')).toBe('MMM');
    expect(component.trendDateFormat('DAILY')).toBe('MMM d');
    const periods = dashboard().batchHealthTrend as never[];
    expect(component.trendMaximum(periods)).toBe(5);
    expect(component.trendMaximum([])).toBe(1);
    expect(component.trendBarHeight(periods[0], periods)).toBe(100);
    expect(component.trendSegmentHeight(1, 4)).toBe(25);
    expect(component.trendSegmentHeight(1, 0)).toBe(0);
    expect(component.showTrendSegmentLabel(5, periods)).toBe(true);
    expect(component.showTrendSegmentLabel(0, periods)).toBe(false);
    expect(component.showTrendSegmentLabel(0.5, periods)).toBe(false);
    expect(component.trendMinimumWidth(5, 'DAILY')).toBe(720);
    expect(component.trendMinimumWidth(30, 'DAILY')).toBe(1260);
    expect(component.trendMinimumWidth(30, 'WEEKLY')).toBe(1980);
    expect(component.trendMinimumWidth(30, 'MONTHLY')).toBe(2460);
  });

  it('computes rolling periods', () => {
    start();
    component.reportPeriod.set('TODAY');
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    const today = dashboardRequest();
    expect(today.request.params.get('fromDate')).toBe(today.request.params.get('toDate'));
    today.flush(dashboard());
    component.reportPeriod.set('LAST_30_DAYS');
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    dashboardRequest().flush(dashboard());
  });

  it('cancels a stale dashboard request when a newer one starts', () => {
    start();
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    const first = dashboardRequest();
    component.applyFilters({ preventDefault: jest.fn() } as unknown as SubmitEvent);
    expect(first.cancelled).toBe(true);
    dashboardRequest().flush(dashboard());
  });

  it('renders every section with data', () => {
    start({}, dashboard({ trendGranularity: 'WEEKLY' }));
    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(text).toContain('RG 1');
  });
});
