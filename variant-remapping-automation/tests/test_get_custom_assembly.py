import os
import unittest
from unittest.mock import patch, PropertyMock

from get_custom_assembly import CustomAssembly
from remapping_config import load_config


class TestCustomAssembly(unittest.TestCase):
    resources_folder = os.path.join(os.path.dirname(__file__), 'resources')

    def setUp(self) -> None:
        config_file = os.path.join(self.resources_folder, 'remapping_config.yml')
        load_config(config_file)
        assembly_accession = 'GCA_000003055.3'
        assembly_report_path = os.path.join(self.resources_folder, 'GCA_000003055.3_assembly_report.txt')
        assembly_fasta_path = os.path.join(self.resources_folder, 'GCA_000003055.3.fa')
        self.assembly = CustomAssembly(assembly_accession, assembly_fasta_path, assembly_report_path)
        self.patch_required_contigs = patch.object(CustomAssembly, 'required_contigs',
                                                   return_value=PropertyMock(return_value=['AY526085.1']))
        self.current_dir = os.getcwd()
        os.chdir(os.path.dirname(os.path.dirname(__file__)))

    def tearDown(self) -> None:
        for f in [self.assembly.output_assembly_report_path, self.assembly.output_assembly_fasta_path,
                  os.path.join(self.resources_folder, 'AY526085.1.fa')]:
            if os.path.exists(f):
                os.remove(f)

    def test_assembly_report_rows(self):
        first_row = {
            '# Sequence-Name': 'Chr1', 'Sequence-Role': 'assembled-molecule', 'Assigned-Molecule': '1',
            'Assigned-Molecule-Location/Type': 'Chromosome', 'GenBank-Accn': 'GK000001.2', 'Relationship': '=',
            'RefSeq-Accn': 'AC_000158.1', 'Assembly-Unit': 'Primary Assembly', 'Sequence-Length': '158337067',
            'UCSC-style-name': 'na'
        }
        assert self.assembly.assembly_report_rows[0] == first_row

    def test_download_contig_from_ncbi(self):
        self.assembly.download_contig_from_ncbi('AY526085.1')

    def test_write_ncbi_assembly_report(self):
        with self.patch_required_contigs:
            self.assembly.generate_assembly_report()

    def test_construct_fasta_from_report(self):
        with self.patch_required_contigs:
            self.assembly.generate_fasta()
