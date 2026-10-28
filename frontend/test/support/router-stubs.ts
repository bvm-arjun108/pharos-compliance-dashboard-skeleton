import { convertToParamMap, ParamMap, Params } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

/** Minimal ActivatedRoute whose query params the test can change and re-emit. */
export class RouteStub {
  readonly params$ = new BehaviorSubject<ParamMap>(convertToParamMap({}));
  readonly queryParamMap = this.params$.asObservable();
  snapshot: { data: Record<string, unknown>; queryParamMap: ParamMap } = {
    data: {},
    queryParamMap: convertToParamMap({})
  };

  setQuery(params: Params): void {
    const map = convertToParamMap(params);
    this.snapshot = { ...this.snapshot, queryParamMap: map };
    this.params$.next(map);
  }
}

export class RouterStub {
  navigate = jest.fn().mockResolvedValue(true);
  navigateByUrl = jest.fn().mockResolvedValue(true);
}
