import { TestBed } from '@angular/core/testing';
import { DashboardFilterStateService } from '../../src/app/dashboard-filter-state.service';

describe('DashboardFilterStateService', () => {
  it('starts empty and remembers the last filters set', () => {
    const service = TestBed.inject(DashboardFilterStateService);
    expect(service.get()).toBeNull();
    const filters = { batchId: 'B', country: 'US', reportGroupId: '1', reportPeriod: 'CUSTOM' as const, startDate: 'a', endDate: 'b' };
    service.set(filters);
    expect(service.get()).toBe(filters);
  });
});
