import http from 'k6/http';
import { check } from 'k6';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

export const options = {
    duration: '30s',
    vus: 10,
};

export default function () {
    const url = 'http://localhost:8081/request-registration';
    const payload = JSON.stringify({
        email: 'test@example.com'
    });
    const params = {
        headers: {
            'Content-Type': 'application/json',
        },
    };
    const res = http.post(url, payload, params);
    check(res, {
        'is status 204': (r) => r.status === 204,
    });
}

export function handleSummary(data) {
    const checksPassedRate = (data.metrics.checks.values.rate * 100).toFixed(2);
    const httpReqFailedRate = (data.metrics.http_req_failed.values.rate * 100).toFixed(2);

    const summary = {
        'Number of iterations': data.metrics.iterations.values.count,
        'Percentage of checks passed': `${checksPassedRate}%`,
        'Median HTTP request duration (ms)': data.metrics.http_req_duration.values.med.toFixed(2),
        'HTTP requests failed': `${httpReqFailedRate}%`,
    };

    const jsonContent = JSON.stringify(summary, null, 2);

    return {
        'stdout': textSummary(data, { indent: ' ', enableColors: true }),
        'summary.json': jsonContent,
    };
}