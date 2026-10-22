/**
 * This configuration is automatically read by the @angular-builders/jest builder.
 */
module.exports = {
  cache: false,
  preset: 'jest-preset-angular',
  roots: ['<rootDir>/src/'],
  testMatch: ['**/+(*.)+(spec).+(ts)'],
  setupFilesAfterEnv: ['<rootDir>/src/jest.setup.ts'],
  coverageDirectory: 'coverage/dashboard',
  coverageReporters: ['html', 'lcov', 'text-summary'],
  modulePaths: ['<rootDir>/src/'],
  testResultsProcessor: 'jest-sonar-reporter'
};
