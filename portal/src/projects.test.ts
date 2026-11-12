import { describe, expect, it } from 'vitest'
import { projects, selectProjects } from './projects'

describe('project directory', () => {
  it('finds a project by its technical capability without case sensitivity', () => {
    expect(
      selectProjects(projects, '  JAVA 17 ', 'all', 'recent').map((project) => project.id),
    ).toEqual(['foundation-service'])
  })

  it('keeps unimplemented learning projects out of category results', () => {
    expect(selectProjects(projects, '', 'learning', 'name')).toEqual([])
    expect(selectProjects(projects, 'Redis', 'all', 'recent')).toEqual([])
  })

  it('keeps source registry ordering unchanged while filtering and sorting', () => {
    const ids = projects.map((project) => project.id)
    selectProjects(projects, '', 'all', 'name')
    expect(projects.map((project) => project.id)).toEqual(ids)
    expect(new Set(ids).size).toBe(ids.length)
  })
})
