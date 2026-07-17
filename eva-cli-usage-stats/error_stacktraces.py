# Copyright 2026 EMBL - European Bioinformatics Institute
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

import argparse
from collections import defaultdict
from datetime import datetime, timezone, timedelta
from functools import cached_property

from ebi_eva_common_pyutils.logger import logging_config
from ebi_eva_internal_pyutils.metadata_utils import get_metadata_connection_handle
from cli_usage_stats import extract_exception_name

logger = logging_config.get_logger(__name__)

TABLE_NAME = 'eva_submissions.call_home_event'

def remove_path_from_lines(line):
    result = []
    sp_line = line.split()
    for token in sp_line:
        if token.count('/') > 1:
            result.append('<path removed>')
        else:
            result.append(token)
    return ' '.join(result)

def load_excluded_deployment_ids(path):
    if not path:
        return set()
    with open(path) as f:
        return {line.strip() for line in f if line.strip()}


class ErrorStacktraceReporter:

    def __init__(self, private_config_xml_file, profile, start_date, end_date, run_ids, deployment_ids,
                 exclude_deployment_ids):
        self.private_config_xml_file = private_config_xml_file
        self.profile = profile
        self.start_date = start_date
        self.end_date = end_date
        self.run_ids = run_ids
        self.deployment_ids = deployment_ids
        self.exclude_deployment_ids = exclude_deployment_ids

    @cached_property
    def postgres_handle(self):
        return get_metadata_connection_handle(self.profile, self.private_config_xml_file)

    def query(self, sql, params=()):
        with self.postgres_handle.cursor() as cursor:
            cursor.execute(sql, params)
            return cursor.fetchall()

    def failures(self):
        sql = f"""
            SELECT run_id, deployment_id, created_at, raw_payload
            FROM {TABLE_NAME}
            WHERE event_type = 'FAILURE'
              AND created_at >= %s
              AND created_at < %s
              {"AND run_id = ANY(%s)" if self.run_ids else ""}
              {"AND deployment_id = ANY(%s)" if self.deployment_ids else ""}
              {"AND deployment_id != ALL(%s)" if self.exclude_deployment_ids else ""}
            ORDER BY created_at
        """
        params = [self.start_date, self.end_date]
        if self.run_ids:
            params.append(self.run_ids)
        if self.deployment_ids:
            params.append(self.deployment_ids)
        if self.exclude_deployment_ids:
            params.append(self.exclude_deployment_ids)
        return self.query(sql, tuple(params))

    def print_failures(self):
        rows = self.failures()
        logger.info(f"Found {len(rows)} matching FAILURE event(s)")
        exception_dict = defaultdict(list)
        for run_id, deployment_id, created_at, raw_payload in rows:
            try:
                stacktrace = raw_payload.get('exceptionStacktrace') or '(no stacktrace available)'
                exception_name, exception_line = extract_exception_name(stacktrace)
                exception_line = remove_path_from_lines(exception_line)
            except AttributeError:
                stacktrace = '(unparseable payload)'
                exception_name = 'N/A'
                exception_line = 'N/A'
            exception_dict[(exception_name, exception_line)].append({
                'created_at': created_at,
                'deployment_id': deployment_id,
                'run_id': run_id,
                'stacktrace': stacktrace
            })
        for exception_name, exception_line in sorted(exception_dict):
            exceptions = exception_dict[(exception_name, exception_line)]
            print(f"NEW TYPE: {exception_line} {len(exceptions)} events")
            for exception in exceptions:
                print('=' * 80)
                print(f"Date: {exception['created_at']}")
                print(f"Deployment ID: {exception['deployment_id']}")
                print(f"Run ID: {exception['run_id']}")
                print('-' * 80)
                print(exception['stacktrace'])


def parse_date(value):
    return datetime.strptime(value, '%Y-%m-%d').replace(tzinfo=timezone.utc)


def main():
    parser = argparse.ArgumentParser(description='Print full stacktraces of recent CLI errors.')
    parser.add_argument('--private_config_xml_file', required=True,
                        help='Path to the Maven settings XML file with database credentials.')
    parser.add_argument('--profile', default='production_processing',
                        help='Profile name in the Maven settings XML (default: production_processing).')
    parser.add_argument('--start-date', type=parse_date,
                        help='Only include errors on or after this date (YYYY-MM-DD). '
                             'Defaults to 14 days ago.')
    parser.add_argument('--end-date', type=parse_date,
                        help='Only include errors strictly before this date (YYYY-MM-DD), '
                             'i.e. the given day is included in full. Defaults to now.')
    parser.add_argument('--run-id', nargs='+', dest='run_ids',
                        help='Only include errors for these run ID(s).')
    parser.add_argument('--deployment-id', nargs='+', dest='deployment_ids',
                        help='Only include errors for these deployment ID(s).')
    parser.add_argument('--exclude-deployment-id', nargs='+', dest='exclude_deployment_ids',
                        help='Exclude errors for these deployment ID(s).')
    parser.add_argument('--exclude_deployment_ids_file',
                        help='File with deployment IDs to exclude (one per line), '
                             'same format as cli_usage_stats.py.')
    args = parser.parse_args()
    logging_config.add_stderr_handler()
    now = datetime.now(timezone.utc)
    start_date = args.start_date or now - timedelta(days=14)
    end_date = (args.end_date + timedelta(days=1)) if args.end_date else now
    excluded_ids = set(args.exclude_deployment_ids or []) | load_excluded_deployment_ids(
        args.exclude_deployment_ids_file)
    ErrorStacktraceReporter(
        private_config_xml_file=args.private_config_xml_file,
        profile=args.profile,
        start_date=start_date,
        end_date=end_date,
        run_ids=args.run_ids,
        deployment_ids=args.deployment_ids,
        exclude_deployment_ids=list(excluded_ids) if excluded_ids else None,
    ).print_failures()


if __name__ == '__main__':
    main()
