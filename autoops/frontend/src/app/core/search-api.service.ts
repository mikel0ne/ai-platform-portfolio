import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { SearchRequest, SearchResponse } from './models';

@Injectable({ providedIn: 'root' })
export class SearchApiService {
  private readonly http = inject(HttpClient);

  search(req: SearchRequest): Observable<SearchResponse> {
    return this.http.post<SearchResponse>('/search', req);
  }
}
