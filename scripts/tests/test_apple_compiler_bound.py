import sys
from pathlib import Path
import unittest
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from hosted_apple_acceptance import validate_compiler_log

VALID = 'builtin-SwiftDriver -- /tool/swiftc -module-name FoodBlob -Onone -whole-module-optimization -j1 -num-threads 1 -j3 -no-emit-module-separately-wmo'


class AppleCompilerBoundTests(unittest.TestCase):
    def test_supported_wmo_retains_planning_jobs_and_thread_one_proof(self):
        proof = validate_compiler_log(VALID)
        self.assertEqual(proof[0]['planning_jobs'], ['-j1', '-j3'])
        self.assertEqual(proof[0]['frontend_threads'], ['1'])

    def test_true_multithread_batch_non_wmo_or_missing_proof_fails(self):
        for text in (VALID + ' -num-threads 3', VALID + ' -enable-batch-mode',
                     VALID.replace('-whole-module-optimization', ''),
                     VALID.replace('-num-threads 1', ''), 'TEST BUILD SUCCEEDED'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                validate_compiler_log(text)
