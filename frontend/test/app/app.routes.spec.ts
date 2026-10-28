import { Location } from '@angular/common';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter, Router } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';
import { routes } from '../../src/app/app.routes';
import { PlaceholderViewComponent } from '../../src/app/placeholder-view.component';

describe('routes', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideRouter(routes), provideHttpClient(), provideHttpClientTesting()] });
  });

  it('redirects the empty and unknown paths to the batch view', async () => {
    const router = TestBed.inject(Router);
    const location = TestBed.inject(Location);
    await router.navigateByUrl('/');
    expect(location.path()).toBe('/batches');
    await router.navigateByUrl('/does-not-exist');
    expect(location.path()).toBe('/batches');
  });

  it('declares every screen with a title', () => {
    const titles = routes.filter(route => route.title).map(route => route.title);
    expect(titles).toEqual(
      expect.arrayContaining(['Batch Explorer | Pharos', 'Batch View | Pharos', 'Transaction View | Pharos', 'Report Config | Pharos', 'Transactions | Pharos', 'Rules | Pharos'])
    );
  });

  it('renders the planned-view placeholders with their route data', async () => {
    const harness = await RouterTestingHarness.create();
    const rules = await harness.navigateByUrl('/rules', PlaceholderViewComponent);
    expect(rules.view.title).toBe('Rules');
    const control = await harness.navigateByUrl('/batches/control-room/1/B1/1', PlaceholderViewComponent);
    expect(control.view.title).toBe('Batch Control Room');
  });
});
