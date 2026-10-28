import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router } from '@angular/router';
import { ReportConfigComponent } from '../../src/app/report-config.component';
import { RouteStub, RouterStub } from '../support/router-stubs';

const listItem = (id: number, transformer = 'tv/1') => ({
  reportGroupId: id,
  reportGroupName: `RG ${id}`,
  reportSelectionVersionId: 2,
  transformerVersionId: transformer,
  countryCode: 'US',
  countryName: 'United States',
  regionName: null,
  reportType: 'CTR',
  active: true,
  partialReport: false,
  databaseLookupEnabled: true,
  mappingServiceName: null,
  modifiedAt: '2026-09-01T00:00:00Z'
});

const explorer = (configurations: ReturnType<typeof listItem>[]) => ({
  summary: {
    totalConfigurations: configurations.length,
    activeConfigurations: configurations.length,
    representedCountries: 1,
    objectiveConfigurations: 1,
    subjectiveConfigurations: 0
  },
  configurations,
  country: 'ALL',
  status: 'ALL',
  reportType: 'ALL',
  reportGroupId: null
});

const details = (overrides: Record<string, unknown> = {}) => ({
  identity: {
    reportGroupId: 1,
    reportGroupName: 'RG 1',
    businessGroupName: null,
    countryCode: 'US',
    countryName: 'United States',
    threeLetterCountryCode: 'USA',
    regionCode: null,
    regionName: null,
    reportType: 'CTR',
    active: true
  },
  versioning: { reportSelectionVersionId: 2, transformerVersionId: 'tv/1', createdAt: null, modifiedAt: null },
  processingBehavior: {
    databaseLookupEnabled: true,
    blankReport: false,
    nonTransactionalReport: false,
    partialReport: false,
    reportPeriod: null,
    additionalData: null
  },
  mapping: {
    projectKey: null,
    serviceName: null,
    acknowledgementDocumentSubtype: null,
    outputFileDocumentSubtype: null,
    submissionDocumentSubtype: null,
    transformerConfig: null
  },
  rules: {
    inboundRuleId: null,
    outboundRuleId: null,
    reportSelection: null,
    reportableActivityColumns: null,
    ruleHitColumns: null
  },
  strategies: {
    exclusionStrategy: null,
    exclusionReason: null,
    columnToCompare: null,
    manipulationStrategyMetadata: null,
    reconciliationStrategyMetadata: null
  },
  ...overrides
});

describe('ReportConfigComponent', () => {
  let fixture: ComponentFixture<ReportConfigComponent>;
  let component: ReportConfigComponent;
  let http: HttpTestingController;
  let route: RouteStub;
  let router: RouterStub;

  beforeEach(async () => {
    route = new RouteStub();
    router = new RouterStub();
    await TestBed.configureTestingModule({
      imports: [ReportConfigComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: ActivatedRoute, useValue: route },
        { provide: Router, useValue: router }
      ]
    }).compileComponents();
    fixture = TestBed.createComponent(ReportConfigComponent);
    component = fixture.componentInstance;
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  const start = (query: Record<string, string> = {}) => {
    route.setQuery(query);
    fixture.detectChanges();
  };
  const answerOptions = () =>
    http.expectOne('/api/v1/report-configs/filter-options').flush({ countries: [{ code: 'US', name: 'United States' }], reportTypes: ['CTR'] });
  const answerList = (configs: ReturnType<typeof listItem>[]) =>
    http.expectOne(req => req.url === '/api/v1/report-configs').flush(explorer(configs));

  it('loads filter options, configurations and the first configuration details', () => {
    start();
    answerOptions();
    answerList([listItem(1), listItem(2)]);
    http.expectOne('/api/v1/report-configs/1/2/tv%2F1').flush(details());
    fixture.detectChanges();

    expect(component.filterOptions().countries).toHaveLength(1);
    expect(component.selectedConfig()?.reportGroupId).toBe(1);
    expect(component.selectedDetails()).not.toBeNull();
    expect(component.detailLoading()).toBe(false);
  });

  it('reads filters from the route and sends them to the API', () => {
    start({ country: 'CA', status: 'ACTIVE', reportType: 'SAR', reportGroupId: '12ab3' });
    answerOptions();
    const request = http.expectOne(req => req.url === '/api/v1/report-configs');
    expect(request.request.params.get('country')).toBe('CA');
    expect(request.request.params.get('status')).toBe('ACTIVE');
    expect(request.request.params.get('reportType')).toBe('SAR');
    expect(request.request.params.get('reportGroupId')).toBe('123');
    request.flush(explorer([]));
    expect(component.selectedConfig()).toBeNull();
  });

  it('falls back to ALL for an unknown status', () => {
    start({ status: 'WHATEVER' });
    answerOptions();
    answerList([]);
    expect(component.status()).toBe('ALL');
  });

  it('shows an error when the list cannot be loaded and empties filter options on failure', () => {
    start();
    http.expectOne('/api/v1/report-configs/filter-options').flush('x', { status: 500, statusText: 'err' });
    http.expectOne(req => req.url === '/api/v1/report-configs').flush('x', { status: 500, statusText: 'err' });
    expect(component.listError()).toContain('could not be loaded');
    expect(component.response()).toBeNull();
    expect(component.filterOptions().countries).toEqual([]);
  });

  it('shows an error when the details cannot be loaded', () => {
    start();
    answerOptions();
    answerList([listItem(1)]);
    http.expectOne('/api/v1/report-configs/1/2/tv%2F1').flush('x', { status: 404, statusText: 'nf' });
    expect(component.detailError()).toContain('could not be loaded');
    expect(component.detailLoading()).toBe(false);
  });

  it('selects another configuration, ignores re-selecting it, and drops stale detail responses', () => {
    start();
    answerOptions();
    answerList([listItem(1), listItem(2)]);
    http.expectOne('/api/v1/report-configs/1/2/tv%2F1').flush(details());

    component.selectConfig(listItem(1));
    http.expectNone('/api/v1/report-configs/1/2/tv%2F1');

    component.selectConfig(listItem(2));
    const first = http.expectOne('/api/v1/report-configs/2/2/tv%2F1');
    component.selectConfig(listItem(3));
    const second = http.expectOne('/api/v1/report-configs/3/2/tv%2F1');
    first.flush(details({ identity: { ...details().identity, reportGroupId: 2 } }));
    expect(component.selectedDetails()).toBeNull();
    second.flush(details({ identity: { ...details().identity, reportGroupId: 3 } }));
    expect(component.selectedDetails()?.identity.reportGroupId).toBe(3);
  });

  it('updates the route when filters are applied or cleared', () => {
    start();
    answerOptions();
    answerList([]);
    component.setCountry('CA');
    component.setStatus({ target: { value: 'INACTIVE' } } as unknown as Event);
    component.setReportType({ target: { value: 'SAR' } } as unknown as Event);
    const input = { value: 'a1b2c3' } as HTMLInputElement;
    component.setReportGroupId({ target: input } as unknown as Event);
    expect(input.value).toBe('123');

    const preventDefault = jest.fn();
    component.applyFilters({ preventDefault } as unknown as SubmitEvent);
    expect(preventDefault).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith([], expect.objectContaining({ queryParams: { country: 'CA', status: 'INACTIVE', reportType: 'SAR', reportGroupId: '123' } }));

    component.setReportGroupId({ target: { value: '' } } as unknown as Event);
    component.applyFilters({ preventDefault } as unknown as SubmitEvent);
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: expect.objectContaining({ reportGroupId: null }) }));

    component.clearFilters();
    expect(router.navigate).toHaveBeenLastCalledWith([], expect.objectContaining({ queryParams: { country: 'ALL', status: 'ALL', reportType: 'ALL', reportGroupId: null } }));
  });

  it('formats display values', () => {
    expect(component.configKey(null)).toBe('');
    expect(component.configKey(listItem(1))).toBe('1:2:tv/1');
    expect(component.configuredValue(null)).toBe('Not configured');
    expect(component.configuredValue(undefined)).toBe('Not configured');
    expect(component.configuredValue('')).toBe('Not configured');
    expect(component.configuredValue(0)).toBe('0');
    expect(component.booleanLabel(true)).toBe('Enabled');
    expect(component.booleanLabel(false)).toBe('Disabled');
    expect(component.csvValues(null)).toEqual([]);
    expect(component.csvValues(' a, b ,,c ')).toEqual(['a', 'b', 'c']);
  });

  it('builds strategy cards from empty, JSON, double-encoded and malformed metadata', () => {
    const empty = component.strategyCards(details() as never);
    expect(empty.every(card => !card.configured)).toBe(true);
    expect(empty[0].preview).toContain('No reconciliation');

    const json = component.strategyCards(
      details({
        strategies: {
          exclusionStrategy: 'BY_RULE',
          exclusionReason: 'ALREADY_REPORTED',
          columnToCompare: 'amount',
          manipulationStrategyMetadata: JSON.stringify({ maxRows: 5, nestedList: ['A_B', 2], obj: { a: 1 }, lower: 'x' }),
          reconciliationStrategyMetadata: JSON.stringify(JSON.stringify({ check_type: 'STRICT' }))
        },
        mapping: { ...details().mapping, transformerConfig: 'not json' }
      }) as never
    );
    expect(json[0].entries).toEqual([{ label: 'Check type', value: 'Strict' }]);
    expect(json[1].entries.map(entry => entry.label)).toEqual(
      expect.arrayContaining(['Exclusion strategy', 'Exclusion reason', 'Comparison column', 'Max rows', 'Nested list', 'Obj', 'Lower'])
    );
    expect(json[1].entries.find(entry => entry.label === 'Exclusion reason')?.value).toBe('Already Reported');
    expect(json[1].entries.find(entry => entry.label === 'Nested list')?.value).toBe('A B, 2');
    expect(json[1].entries.find(entry => entry.label === 'Obj')?.value).toBe('{"a":1}');
    expect(json[2].entries).toEqual([{ label: 'Configuration', value: 'not json' }]);
    expect(json[2].preview).toBe('not json');

    const odd = component.strategyCards(
      details({
        strategies: {
          ...details().strategies,
          reconciliationStrategyMetadata: '[1,2]',
          manipulationStrategyMetadata: JSON.stringify('plain string')
        },
        mapping: { ...details().mapping, transformerConfig: '42' }
      }) as never
    );
    expect(odd[0].entries).toEqual([{ label: 'Configuration', value: '1, 2' }]);
    expect(odd[1].entries).toEqual([{ label: 'Configuration', value: 'plain string' }]);
    expect(odd[2].entries).toEqual([{ label: 'Configuration', value: '42' }]);
  });

  it('renders the loaded state', () => {
    start();
    answerOptions();
    answerList([listItem(1)]);
    http.expectOne('/api/v1/report-configs/1/2/tv%2F1').flush(
      details({ strategies: { ...details().strategies, reconciliationStrategyMetadata: '{"a":"b"}' } })
    );
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).textContent).toContain('RG 1');
  });
});
