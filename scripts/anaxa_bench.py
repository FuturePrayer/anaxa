#!/usr/bin/env python3
import argparse
from collections import Counter
import http.client
import json
import math
import statistics
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from typing import Any
from urllib.parse import urlparse


@dataclass(frozen=True)
class BenchmarkConfig:
    base_url: str
    collection: str
    dimension: int
    metric: str
    flush_threshold_bytes: int
    vector_count: int
    ingest_batch_size: int
    search_requests: int
    warmup_requests: int
    search_workers: int
    top_k: int
    api_key: str | None
    tenant_id: str | None
    group_count: int
    use_filter: bool
    skip_create: bool
    skip_ingest: bool
    flush_after_ingest: bool
    compact_after_ingest: bool
    wait_for_quiescent_seconds: float
    timeout_seconds: float


class HttpJsonClient:
    def __init__(self, base_url: str, api_key: str | None, tenant_id: str | None, timeout: float) -> None:
        parsed = urlparse(base_url)
        if parsed.scheme not in ("http", "https"):
            raise ValueError(f"Unsupported URL scheme: {parsed.scheme}")
        if not parsed.hostname:
            raise ValueError(f"Invalid base URL: {base_url}")
        self._scheme = parsed.scheme
        self._host = parsed.hostname
        self._port = parsed.port or (443 if parsed.scheme == "https" else 80)
        self._base_path = parsed.path.rstrip("/")
        self._api_key = api_key
        self._tenant_id = tenant_id
        self._timeout = timeout
        self._connection: http.client.HTTPConnection | http.client.HTTPSConnection | None = None
        self._lock = threading.Lock()

    def close(self) -> None:
        with self._lock:
            if self._connection is not None:
                self._connection.close()
                self._connection = None

    def request_json(self, method: str, path: str, payload: Any | None = None) -> tuple[int, Any]:
        status, body = self.request(method, path, payload)
        try:
            return status, json.loads(body) if body else None
        except json.JSONDecodeError as exc:
            raise RuntimeError(f"Non-JSON response for {method} {path}: {body[:256]!r}") from exc

    def request(self, method: str, path: str, payload: Any | None = None) -> tuple[int, str]:
        body = b""
        headers = {"Accept": "application/json"}
        if payload is not None:
            body = json.dumps(payload, separators=(",", ":"), ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if self._api_key:
            headers["X-API-Key"] = self._api_key
        if self._tenant_id:
            headers["X-Tenant-Id"] = self._tenant_id

        target_path = f"{self._base_path}{path}" if self._base_path else path
        connection = self._connection_or_open()
        try:
            connection.request(method, target_path, body=body, headers=headers)
            response = connection.getresponse()
            data = response.read().decode("utf-8")
            return response.status, data
        except Exception:
            self.close()
            raise

    def _connection_or_open(self) -> http.client.HTTPConnection | http.client.HTTPSConnection:
        with self._lock:
            if self._connection is None:
                if self._scheme == "https":
                    self._connection = http.client.HTTPSConnection(self._host, self._port, timeout=self._timeout)
                else:
                    self._connection = http.client.HTTPConnection(self._host, self._port, timeout=self._timeout)
            return self._connection


def parse_args() -> BenchmarkConfig:
    parser = argparse.ArgumentParser(description="Benchmark AnaxaDB ingest and search performance.")
    parser.add_argument("--base-url", default="http://127.0.0.1:30720", help="Server base URL.")
    parser.add_argument("--collection", default=f"bench-{int(time.time())}", help="Target collection name.")
    parser.add_argument("--dimension", type=int, default=128, help="Vector dimension.")
    parser.add_argument("--metric", default="COSINE", choices=("COSINE", "L2"), help="Collection metric.")
    parser.add_argument("--flush-threshold-bytes", type=int, default=64 * 1024 * 1024, help="Collection flush threshold.")
    parser.add_argument("--vectors", dest="vector_count", type=int, default=10000, help="Vectors to ingest.")
    parser.add_argument("--ingest-batch-size", type=int, default=200, help="Vectors per upsert request.")
    parser.add_argument("--search-requests", type=int, default=2000, help="Measured search request count.")
    parser.add_argument("--warmup-requests", type=int, default=200, help="Warmup search request count.")
    parser.add_argument("--search-workers", type=int, default=8, help="Concurrent search workers.")
    parser.add_argument("--top-k", type=int, default=10, help="Search topK.")
    parser.add_argument("--api-key", help="Optional X-API-Key.")
    parser.add_argument("--tenant-id", help="Optional X-Tenant-Id.")
    parser.add_argument("--group-count", type=int, default=16, help="Payload group cardinality.")
    parser.add_argument("--use-filter", action="store_true", help="Add payload group filters to search requests.")
    parser.add_argument("--skip-create", action="store_true", help="Skip collection creation.")
    parser.add_argument("--skip-ingest", action="store_true", help="Skip vector ingestion.")
    parser.add_argument("--flush-after-ingest", action="store_true", help="Call the flush endpoint before search.")
    parser.add_argument("--compact-after-ingest", action="store_true", help="Call the compact endpoint after flush before search.")
    parser.add_argument("--wait-for-quiescent-seconds", type=float, default=60.0, help="Max time to wait for flush/compaction to settle.")
    parser.add_argument("--timeout-seconds", type=float, default=30.0, help="HTTP request timeout.")
    args = parser.parse_args()

    if args.dimension <= 0 or args.vector_count <= 0 or args.ingest_batch_size <= 0:
        parser.error("dimension, vectors, and ingest-batch-size must be positive")
    if args.search_requests <= 0 or args.warmup_requests < 0 or args.search_workers <= 0 or args.top_k <= 0:
        parser.error("search requests, workers, and top-k must be valid positive values")
    if args.group_count < 0:
        parser.error("group-count must not be negative")

    config = BenchmarkConfig(
        base_url=args.base_url,
        collection=args.collection,
        dimension=args.dimension,
        metric=args.metric,
        flush_threshold_bytes=args.flush_threshold_bytes,
        vector_count=args.vector_count,
        ingest_batch_size=args.ingest_batch_size,
        search_requests=args.search_requests,
        warmup_requests=args.warmup_requests,
        search_workers=args.search_workers,
        top_k=args.top_k,
        api_key=args.api_key,
        tenant_id=args.tenant_id,
        group_count=args.group_count,
        use_filter=args.use_filter,
        skip_create=args.skip_create,
        skip_ingest=args.skip_ingest,
        flush_after_ingest=args.flush_after_ingest,
        compact_after_ingest=args.compact_after_ingest,
        wait_for_quiescent_seconds=args.wait_for_quiescent_seconds,
        timeout_seconds=args.timeout_seconds,
    )
    return config


def vector_for(index: int, dimension: int) -> list[float]:
    vector = [0.0] * dimension
    vector[index % dimension] = 1.0
    vector[(index * 7 + 3) % dimension] += 0.35
    vector[(index * 11 + 5) % dimension] += 0.2
    vector[(index * 13 + 1) % dimension] += ((index % 5) + 1) / 20.0
    return vector


def payload_for(index: int, group_count: int) -> dict[str, Any]:
    payload: dict[str, Any] = {
        "bucket": index % 1024,
        "source": f"repo-{index % max(1, group_count or 1)}",
        "path": f"/docs/{index % 97}/note-{index}.md",
        "section": index % 12,
    }
    if group_count > 0:
        payload["group"] = f"g-{index % group_count}"
    return payload


def create_collection(client: HttpJsonClient, config: BenchmarkConfig) -> None:
    if config.skip_create:
        return
    status, body = client.request_json(
        "POST",
        "/collections",
        {
            "name": config.collection,
            "dimension": config.dimension,
            "metric": config.metric,
            "flushThresholdBytes": config.flush_threshold_bytes,
        },
    )
    if status not in (200, 201):
        raise RuntimeError(f"Create collection failed: HTTP {status} {body}")


def ingest_vectors(client: HttpJsonClient, config: BenchmarkConfig) -> tuple[float, dict[str, Any] | None]:
    if config.skip_ingest:
        return 0.0, None

    started_at = time.perf_counter()
    latest_stats = None
    for start in range(0, config.vector_count, config.ingest_batch_size):
        end = min(config.vector_count, start + config.ingest_batch_size)
        payload = {
            "vectors": [
                {
                    "id": f"doc-{index}",
                    "vector": vector_for(index, config.dimension),
                    "payload": payload_for(index, config.group_count),
                }
                for index in range(start, end)
            ]
        }
        status, body = client.request_json("POST", f"/collections/{config.collection}/vectors", payload)
        if status != 200:
            raise RuntimeError(f"Upsert failed for batch {start}:{end}: HTTP {status} {body}")
        latest_stats = body
    return time.perf_counter() - started_at, latest_stats


def run_search_phase(
    config: BenchmarkConfig,
    request_count: int,
    timeout_seconds: float,
) -> tuple[float, list[float], int, dict[str, int]]:
    requests_per_worker = [request_count // config.search_workers] * config.search_workers
    for index in range(request_count % config.search_workers):
        requests_per_worker[index] += 1

    started_at = time.perf_counter()
    latencies: list[float] = []
    total_errors = 0
    status_counts: Counter[str] = Counter()
    with ThreadPoolExecutor(max_workers=config.search_workers) as executor:
        futures = [
            executor.submit(run_search_worker, config, timeout_seconds, worker_id, requests_per_worker[worker_id])
            for worker_id in range(config.search_workers)
            if requests_per_worker[worker_id] > 0
        ]
        for future in futures:
            worker_latencies, worker_errors, worker_status_counts = future.result()
            latencies.extend(worker_latencies)
            total_errors += worker_errors
            status_counts.update(worker_status_counts)
    return time.perf_counter() - started_at, latencies, total_errors, dict(status_counts)


def run_search_worker(
    config: BenchmarkConfig,
    timeout_seconds: float,
    worker_id: int,
    request_count: int,
) -> tuple[list[float], int, dict[str, int]]:
    client = HttpJsonClient(config.base_url, config.api_key, config.tenant_id, timeout_seconds)
    latencies: list[float] = []
    errors = 0
    status_counts: Counter[str] = Counter()
    try:
        for iteration in range(request_count):
            vector_index = (worker_id + iteration * config.search_workers) % config.vector_count
            payload: dict[str, Any] = {
                "vector": vector_for(vector_index, config.dimension),
                "topK": config.top_k,
                "filter": {},
            }
            if config.use_filter and config.group_count > 0:
                payload["filter"] = {"group": f"g-{vector_index % config.group_count}"}

            started_at = time.perf_counter()
            status, body = client.request_json("POST", f"/collections/{config.collection}/search", payload)
            elapsed_ms = (time.perf_counter() - started_at) * 1000.0
            status_counts[str(status)] += 1
            if status != 200:
                errors += 1
                continue
            if not isinstance(body, dict) or "hits" not in body:
                errors += 1
                status_counts["invalid-body"] += 1
                continue
            latencies.append(elapsed_ms)
    finally:
        client.close()
    return latencies, errors, dict(status_counts)


def fetch_collection_stats(client: HttpJsonClient, collection: str) -> dict[str, Any] | None:
    status, body = client.request_json("GET", f"/collections/{collection}")
    if status != 200 or not isinstance(body, dict):
        return None
    return body


def flush_collection(client: HttpJsonClient, config: BenchmarkConfig) -> dict[str, Any] | None:
    status, body = client.request_json("POST", f"/collections/{config.collection}/flush", {})
    if status != 200:
        raise RuntimeError(f"Flush failed: HTTP {status} {body}")
    return body if isinstance(body, dict) else None


def compact_collection(client: HttpJsonClient, config: BenchmarkConfig) -> dict[str, Any] | None:
    status, body = client.request_json("POST", f"/collections/{config.collection}/compact", {})
    if status != 200:
        raise RuntimeError(f"Compaction failed: HTTP {status} {body}")
    return body if isinstance(body, dict) else None


def wait_for_quiescent(client: HttpJsonClient, collection: str, timeout_seconds: float) -> dict[str, Any]:
    deadline = time.perf_counter() + timeout_seconds
    last_stats: dict[str, Any] | None = None
    while time.perf_counter() < deadline:
        stats = fetch_collection_stats(client, collection)
        if stats is None:
            raise RuntimeError(f"Failed to fetch collection stats for {collection}")
        last_stats = stats
        if not stats.get("flushInProgress") and not stats.get("compactionInProgress"):
            return stats
        time.sleep(0.05)
    raise RuntimeError(f"Collection {collection} did not become quiescent within {timeout_seconds} seconds: {last_stats}")


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(len(ordered) * ratio) - 1))
    return ordered[index]


def print_phase_summary(
    title: str,
    duration_seconds: float,
    total_requests: int,
    latencies: list[float],
    errors: int,
    status_counts: dict[str, int] | None = None,
) -> None:
    qps = 0.0 if duration_seconds <= 0 else total_requests / duration_seconds
    success_qps = 0.0 if duration_seconds <= 0 else len(latencies) / duration_seconds
    average_ms = statistics.fmean(latencies) if latencies else 0.0
    print(f"\n[{title}]")
    print(f"  duration_seconds: {duration_seconds:.3f}")
    print(f"  total_requests:   {total_requests}")
    print(f"  successful:       {len(latencies)}")
    print(f"  errors:           {errors}")
    print(f"  throughput_qps:   {qps:.2f}")
    print(f"  successful_qps:   {success_qps:.2f}")
    print(f"  avg_ms:           {average_ms:.3f}")
    print(f"  p50_ms:           {percentile(latencies, 0.50):.3f}")
    print(f"  p95_ms:           {percentile(latencies, 0.95):.3f}")
    print(f"  p99_ms:           {percentile(latencies, 0.99):.3f}")
    print(f"  max_ms:           {max(latencies) if latencies else 0.0:.3f}")
    if status_counts:
        print(f"  status_counts:    {json.dumps(dict(sorted(status_counts.items())), separators=(',', ':'))}")


def main() -> int:
    config = parse_args()
    client = HttpJsonClient(config.base_url, config.api_key, config.tenant_id, config.timeout_seconds)

    try:
        print("[setup]")
        print(f"  base_url:         {config.base_url}")
        print(f"  collection:       {config.collection}")
        print(f"  tenant_id:        {config.tenant_id or '<none>'}")
        print(f"  dimension:        {config.dimension}")
        print(f"  vectors:          {config.vector_count}")
        print(f"  search_workers:   {config.search_workers}")
        print(f"  search_requests:  {config.search_requests}")
        print(f"  use_filter:       {config.use_filter}")
        print(f"  flush_after_ingest: {config.flush_after_ingest}")
        print(f"  compact_after_ingest: {config.compact_after_ingest}")

        create_collection(client, config)
        ingest_seconds, latest_stats = ingest_vectors(client, config)
        if not config.skip_ingest:
            ingest_throughput = config.vector_count / ingest_seconds if ingest_seconds > 0 else 0.0
            print("\n[ingest]")
            print(f"  duration_seconds: {ingest_seconds:.3f}")
            print(f"  throughput_vps:   {ingest_throughput:.2f}")
            if latest_stats:
                print(f"  live_vectors:     {latest_stats.get('liveVectorCount')}")
                print(f"  segments:         {latest_stats.get('segmentCount')}")

        if config.flush_after_ingest or config.compact_after_ingest:
            prepare_started = time.perf_counter()
            prepare_steps: list[str] = []
            prepare_stats = None
            prepare_steps.append("flush")
            prepare_stats = flush_collection(client, config)
            prepare_stats = wait_for_quiescent(client, config.collection, config.wait_for_quiescent_seconds)
            if config.compact_after_ingest:
                prepare_steps.append("compact")
                prepare_stats = compact_collection(client, config)
                prepare_stats = wait_for_quiescent(client, config.collection, config.wait_for_quiescent_seconds)

            print("\n[prepare]")
            print(f"  steps:            {','.join(prepare_steps)}")
            print(f"  duration_seconds: {time.perf_counter() - prepare_started:.3f}")
            if prepare_stats:
                print(f"  live_vectors:     {prepare_stats.get('liveVectorCount')}")
                print(f"  segments:         {prepare_stats.get('segmentCount')}")
                print(f"  storage_bytes:    {prepare_stats.get('storageBytes')}")

        if config.warmup_requests > 0:
            warmup_seconds, warmup_latencies, warmup_errors, warmup_status_counts = run_search_phase(
                config,
                config.warmup_requests,
                config.timeout_seconds,
            )
            print_phase_summary(
                "warmup",
                warmup_seconds,
                config.warmup_requests,
                warmup_latencies,
                warmup_errors,
                warmup_status_counts,
            )

        measured_seconds, measured_latencies, measured_errors, measured_status_counts = run_search_phase(
            config,
            config.search_requests,
            config.timeout_seconds,
        )
        print_phase_summary(
            "measured-search",
            measured_seconds,
            config.search_requests,
            measured_latencies,
            measured_errors,
            measured_status_counts,
        )

        stats = fetch_collection_stats(client, config.collection)
        if stats:
            print("\n[collection-stats]")
            print(f"  tenant_id:        {stats.get('tenantId')}")
            print(f"  live_vectors:     {stats.get('liveVectorCount')}")
            print(f"  segments:         {stats.get('segmentCount')}")
            print(f"  storage_bytes:    {stats.get('storageBytes')}")
        return 0 if measured_errors == 0 else 1
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
