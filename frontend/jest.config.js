/**
 * This configuration is automatically read by the @angular-builders/jest builder.
 */
module.exports = {
  cache: false,
  preset: 'jest-preset-angular',
  roots: ['<rootDir>/test/'],
  testMatch: ['**/+(*.)+(spec).+(ts)'],
  setupFilesAfterEnv: ['<rootDir>/test/jest.setup.ts'],
  collectCoverageFrom: ['src/app/**/*.ts'],
  coverageThreshold: {
    global: { statements: 90, branches: 90, functions: 90, lines: 90 }
  },
  coverageDirectory: 'coverage/dashboard',
  coverageReporters: ['html', 'lcov', 'text-summary'],
  modulePaths: ['<rootDir>/src/', '<rootDir>/test/'],
  testResultsProcessor: 'jest-sonar-reporter'
};
