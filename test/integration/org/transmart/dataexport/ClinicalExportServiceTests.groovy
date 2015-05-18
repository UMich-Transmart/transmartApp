package org.transmart.dataexport

import com.google.common.io.Files
import com.recomdata.asynchronous.JobResultsService
import grails.test.mixin.TestMixin
import org.gmock.WithGMock
import org.junit.Before
import org.junit.Test
import org.transmartproject.core.querytool.Item
import org.transmartproject.core.querytool.Panel
import org.transmartproject.core.querytool.QueryDefinition
import org.transmartproject.db.dataquery.clinical.ClinicalTestData
import org.transmartproject.db.i2b2data.I2b2Data
import org.transmartproject.db.i2b2data.ObservationFact
import org.transmartproject.db.i2b2data.PatientDimension
import org.transmartproject.db.i2b2data.PatientTrialCoreDb
import org.transmartproject.db.ontology.ConceptTestData
import org.transmartproject.db.ontology.I2b2
import org.transmartproject.db.test.RuleBasedIntegrationTestMixin

import static org.hamcrest.MatcherAssert.assertThat
import static org.hamcrest.Matchers.*
import static org.junit.Assert.assertThat
import static org.junit.Assert.assertTrue
import static org.transmartproject.db.TestDataHelper.save

@TestMixin(RuleBasedIntegrationTestMixin)
@WithGMock
class ClinicalExportServiceTests {

    def clinicalExportService
    def queriesResourceService

    File tmpDir
    def queryResult

    ConceptTestData conceptData
    I2b2Data i2b2Data
    List<ObservationFact> facts
    I2b2 studyNode
    I2b2 sexNode


    @Before
    void setUp() {

        //Create some test data
        String trialId = 'STUDY_ID_2'

        List<PatientDimension> patients = I2b2Data.createTestPatients(3, -100, trialId)

        conceptData = ConceptTestData.createDefault()
        List<I2b2> studyNodes = conceptData.i2b2List.findAll { it.cComment?.endsWith(trialId) }

        studyNode = studyNodes.find { it.name == 'study2' }
        sexNode = studyNodes.find { it.name == 'sex' }
        I2b2 femaleNode = studyNodes.find { it.name == 'female' }
        I2b2 maleNode = studyNodes.find { it.name == 'male' }
        I2b2 charNode = studyNodes.find { it.name == 'with%some$characters_' }
        I2b2 study1SubNode = studyNodes.find { it.name == 'study1' }

        List<PatientTrialCoreDb> patientTrials = I2b2Data.createPatientTrialLinks(patients, trialId)
        i2b2Data = new I2b2Data(trialName: trialId, patients: patients, patientTrials: patientTrials)
        facts = ClinicalTestData.createDiagonalCategoricalFacts(
                3,
                [femaleNode, maleNode],
                i2b2Data.patients)
        facts << ClinicalTestData.createObservationFact(charNode.code, i2b2Data.patients[0], -20000, 'test value')
        facts << ClinicalTestData.createObservationFact(study1SubNode.code, i2b2Data.patients[1], -20001, 'foo')

        conceptData.saveAll()
        i2b2Data.saveAll()
        save facts

        tmpDir = Files.createTempDir()

        clinicalExportService.jobResultsService = new JobResultsService(jobResults: [test: [Status: 'In Progress']])

        def definition = new QueryDefinition([
                new Panel(
                        items: [
                                new Item(
                                        conceptKey: studyNode.key.toString()
                                )
                        ]
                )
        ])

        queryResult = queriesResourceService.runQuery(definition, 'test')
    }
    @Test
    void testWithConceptPathSpecified() {
        def file = clinicalExportService.exportClinicalData(
                jobName: 'test',
                resultInstanceId: queryResult.id,
                conceptKeys: [ sexNode.key.toString() ],
                studyDir: tmpDir)

        assertTrue(file.exists())
        assertThat file.absolutePath, endsWith('/data_clinical.tsv')
        assertThat file.length(), greaterThan(0l)
        def table = file.text.split('\n')*.split('\t').collect { it as List }
        assertThat table, contains(
                contains('"Subject ID"', '"\\foo\\study2\\sex\\"'),
                contains('"SUBJ_ID_3"', '"female"'),
                contains('"SUBJ_ID_2"', '"male"'),
                contains('"SUBJ_ID_1"', '"female"'),
        )
    }

    @Test
    void testWithoutConceptPathSpecified() {
        def file = clinicalExportService.exportClinicalData(
                jobName: 'test',
                resultInstanceId: queryResult.id,
                studyDir: tmpDir)

        assertTrue(file.exists())
        assertThat file.absolutePath, endsWith('/data_clinical.tsv')
        assertThat file.length(), greaterThan(0l)
        def table = file.text.split('\n')*.split('\t').collect { it as List }
        assertThat table, contains(
                contains('"Subject ID"', '"\\foo\\study2\\long path\\with%some$characters_\\"',
                        '"\\foo\\study2\\sex\\"', '"\\foo\\study2\\study1\\"'),
                //FIXME Number of columns should be fixed despite on absence of values for the last column
                contains('"SUBJ_ID_3"', '', '"female"'),
                contains('"SUBJ_ID_2"', '', '"male"', '"foo"'),
                contains('"SUBJ_ID_1"', '"test value"', '"female"'),
        )
    }

}
